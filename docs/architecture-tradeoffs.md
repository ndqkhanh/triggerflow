# TriggerFlow — Architecture Trade-offs

Every architectural decision involves a trade-off. This document explains the six most consequential design choices in TriggerFlow, what was sacrificed in each case, and when a different approach would be better.

---

## 1. Three-Tier Deduplication (Bloom → LRU → Persistent)

**What was chosen.** `DeduplicationFilter` orchestrates a three-tier pipeline: `BloomPrefilter` with MurmurHash3 + CAS-based `AtomicLongArray` provides sub-microsecond probabilistic pre-screening; `EventCache` with intrusive LRU provides O(1) exact dedup with bounded memory; `PersistentDedup` provides the durable ground truth for the <0.1% of events that escape both tiers.

**What's sacrificed.**
- **Three stores to reason about**: Each tier has its own capacity, TTL, and failure mode. Debugging a missed duplicate requires tracing through all three tiers.
- **Bloom filter cannot be shrunk**: Once the bit array is allocated (1.7 MB at 1M expected insertions, 0.1% FPR), it cannot be compacted. If the actual insertion count is 100K, 90% of the memory is wasted.
- **No Bloom filter deletion**: Standard Bloom filters don't support element removal. If an event is recorded in the Bloom filter but its action is later rolled back, the Bloom filter permanently considers it "seen." This causes a false negative in dedup for the retried event — the Bloom filter says "maybe seen," the cache and persistent store say "not seen," and the event is correctly processed. No correctness issue, but the Bloom filter wastes a bit position.
- **LRU cache thrashing under scan patterns**: A burst of unique event IDs (e.g., a batch import) evicts hot entries from the LRU cache, temporarily increasing persistent store lookups.

**When the alternative wins.**
- **Single-tier exact dedup (Redis SET)**: If latency tolerance is >1ms and event rate is <100K/sec, a single Redis `SISMEMBER` check provides exact dedup with no probabilistic complexity. Simpler to debug, monitor, and reason about.
- **Counting Bloom filter**: Supports deletion at the cost of 4× memory (4 bits per cell instead of 1). Useful when events are frequently rolled back and re-processed.
- **Cuckoo filter**: Supports deletion with only 1.2× the memory of a Bloom filter. Better choice if deletion is required at scale.

**Engineering judgment.** Three-tier dedup is the right architecture for 1M+ events/sec with a 24-hour dedup window. The Bloom filter eliminates 99%+ of persistent lookups, the LRU cache catches the long tail, and the persistent store provides durability across restarts. The complexity cost is justified by the 1000× latency reduction on the hot path.

---

## 2. Hand-Rolled JSON Parser (vs Jackson/Gson)

**What was chosen.** `JsonCodec` implements a complete recursive descent JSON parser and serializer in ~500 lines of Java. The parser handles the full JSON specification: objects, arrays, strings (with Unicode escape `\uXXXX`), numbers (integers as `Long`, decimals as `Double`), booleans, and null. Error reporting includes character position.

**What's sacrificed.**
- **No streaming parser**: The entire JSON input must fit in memory as a `String`. For multi-megabyte payloads, this is wasteful compared to Jackson's streaming `JsonParser`.
- **No data binding**: Jackson/Gson can deserialize directly into Java records/classes. `JsonCodec` returns `Map<String, Object>` and `List<Object>` — the caller must manually extract and cast fields.
- **No schema validation**: Jackson supports JSON Schema validation via annotations (`@NotNull`, `@Size`). `JsonCodec` provides no validation beyond syntactic correctness.
- **Performance**: Jackson's hand-tuned, JIT-optimized parser processes ~500 MB/sec. `JsonCodec` is estimated at ~50 MB/sec — roughly 10× slower. For TriggerFlow's event payloads (typically 100–500 bytes), this difference is negligible (<1µs vs <0.1µs per event).

**When the alternative wins.**
- **Large payloads (>1 MB)**: Jackson's streaming parser processes data incrementally without buffering the entire input.
- **Complex data binding**: When event payloads must be deserialized into strongly-typed Java objects with validation.
- **Production deployment**: Jackson is the industry standard with a 15-year track record, CVE monitoring, and integration with every Java framework.

**Engineering judgment.** For a portfolio project demonstrating parser implementation skills, a hand-rolled parser is the correct choice. It proves understanding of recursive descent parsing, lexical analysis, and Unicode handling. For production, use Jackson — the performance and ecosystem advantages are overwhelming.

---

## 3. Sealed RuleNode AST (vs Expression String Parsing)

**What was chosen.** Campaign conditions are represented as a sealed `RuleNode` AST with four node types (`AndNode`, `OrNode`, `NotNode`, `ConditionNode`). The `RuleParser` transforms JSON maps into this AST. The `RuleEvaluator` walks the AST using Java 21 pattern matching.

**What's sacrificed.**
- **No string-based rule language**: Operators cannot write rules as expressions like `payload.country == "VN" AND enriched.rides > 10`. They must construct JSON rule trees.
- **No dynamic rule addition**: Adding a new operator (e.g., `BETWEEN`, `REGEX`) requires modifying the `ComparisonOp` enum and recompiling. There is no plugin mechanism for custom operators.
- **No temporal operators**: The rule engine evaluates point-in-time conditions. It cannot express temporal rules like "user completed 3 rides within 24 hours" — these require a stateful event window, which TriggerFlow does not implement.
- **No aggregate functions**: Rules cannot express "average order value > $50" — aggregation requires access to historical data, not a single event payload.

**When the alternative wins.**
- **Expression language (SpEL, MVEL, JEXL)**: For power users who need to write rules as strings with arbitrary logic. SpEL supports method calls, collection projections, and ternary operators. The trade-off is injection risk and unpredictable evaluation cost.
- **CEP engine (Esper, Flink CEP)**: For temporal and aggregate rules. Esper supports patterns like "A followed by B within 10 minutes" with window-based aggregation. The trade-off is a 100× larger runtime dependency and operational complexity.
- **Drools**: For complex business rule management with version control, audit trails, and a web-based rule editor. The trade-off is a massive dependency (~50 JARs) and learning curve.

**Engineering judgment.** The sealed AST approach is optimal for TriggerFlow's scope: simple boolean conditions evaluated at 1M events/sec. The sealed type guarantees exhaustive handling, the JSON input format is machine-friendly (no parser ambiguity), and the weighted short-circuit optimizer works directly on the AST structure. Temporal and aggregate rules are a different problem requiring a different architecture.

---

## 4. Copy-on-Write Campaign Registry (vs ConcurrentHashMap)

**What was chosen.** `CampaignRegistry` stores campaigns in a `volatile Map<String, Campaign>` that is replaced atomically on every write via `Map.copyOf()`. Reads are completely lock-free — just a volatile reference read.

**What's sacrificed.**
- **O(N) write cost**: Every `register()` call copies the entire map. With 10,000 campaigns, each registration allocates and populates a new 10,000-entry map.
- **No partial update**: Updating a single campaign's status requires copying all campaigns. There is no `computeIfPresent()` equivalent — the entire map is replaced.
- **Memory spike during writes**: During the copy, both the old and new maps exist simultaneously. For 10,000 campaigns at 1 KB each, this is a 20 MB transient allocation.

**When the alternative wins.**
- **`ConcurrentHashMap`**: O(1) writes with per-segment locking. Better when writes are frequent (>1/sec) or the map is large (>100K entries). The trade-off is that iterators may see partially-updated state during concurrent modifications.
- **`StampedLock`-guarded `HashMap`**: Optimistic reads with exclusive writes. Better when reads need a consistent snapshot of multiple entries (e.g., iterating all active campaigns). Copy-on-write also provides this, but at higher write cost.

**Engineering judgment.** Copy-on-write is the right choice for TriggerFlow's access pattern: reads at 1M/sec, writes at ~1/hour. The O(N) write cost is irrelevant at 1 write/hour. The lock-free read path is critical at 1M reads/sec. The `volatile` keyword provides happens-before guarantees — every read sees the latest write. This is the same pattern used by `CopyOnWriteArrayList` in the JDK, applied to a map.

---

## 5. Hierarchical Timing Wheel (vs ScheduledExecutorService)

**What was chosen.** `TimingWheel` implements the Varghese & Lauck (1987) hierarchical timing wheel with 100ms tick resolution, 512 slots per wheel, and lazy overflow wheel creation. The `DelayScheduler` wraps the wheel with a scheduling API.

**What's sacrificed.**
- **100ms tick resolution**: Actions cannot be scheduled with sub-100ms precision. A 50ms delay is rounded up to the next tick — effectively 100ms.
- **Tick-driven execution**: Tasks are only fired when `tick()` is called. If the caller falls behind (e.g., GC pause), tasks fire late. There is no self-correcting mechanism — the caller must catch up.
- **No cancel guarantee**: `TimerTask.cancel()` sets a `volatile` flag, but the task may still be in a slot's list. Cancelled tasks are filtered during tick processing, not immediately removed. The slot's memory is not freed until the next tick processes that slot.
- **Slot-level synchronization**: Each slot is guarded by `synchronized`. Under extreme write contention on a single slot (many tasks with similar deadlines), this lock could become a bottleneck.

**When the alternative wins.**
- **`ScheduledThreadPoolExecutor`**: For <1000 pending tasks, the JDK's heap-based delay queue is simpler and provides nanosecond-resolution scheduling. The O(log n) cost is negligible at small n.
- **Hashed wheel timer (Netty `HashedWheelTimer`)**: Same algorithm as TriggerFlow's implementation, but with production hardening: configurable tick resolution, max pending tasks limit, and unprocessed task leak detection.
- **`DelayQueue` + virtual threads**: Java 21's virtual threads make blocking on a `DelayQueue` cheap. One virtual thread per delayed action, parked until the deadline — no timer infrastructure needed. Viable for <100K concurrent delays.

**Engineering judgment.** The timing wheel is the right data structure for TriggerFlow's scale (potentially millions of pending delays). O(1) insert vs. O(log n) heap insert is a real win at n > 10,000. The 100ms tick resolution is acceptable for marketing actions where second-level precision is sufficient. For production, add a catch-up mechanism in `tick()` and a max-pending-tasks limit to prevent memory exhaustion.

---

## 6. In-Memory Persistent Dedup (vs Redis/PostgreSQL)

**What was chosen.** `InMemoryPersistentDedup` implements `PersistentDedup` using `ConcurrentHashMap<EventKey, Instant>`. All dedup state is lost on process restart.

**What's sacrificed.**
- **No crash durability**: If TriggerFlow restarts, the Bloom filter and LRU cache are empty, and the persistent store is gone. All events processed before the restart will be treated as new — potentially triggering duplicate actions.
- **Memory-bounded**: The persistent store grows unboundedly (no TTL, no eviction). Over 24 hours at 1M events/sec (with 30% dedup), ~700K unique events/sec × 86,400 seconds ≈ 60 billion entries. This would require terabytes of memory.
- **No multi-instance sharing**: In a clustered deployment, each instance has its own in-memory store. Event E processed by instance A will not be deduplicated if it arrives at instance B.

**When the alternative wins.**
- **Redis (FlashCache)**: Sub-millisecond lookups with TTL-based expiry. The `SET key EX 86400` command provides exactly the 24-hour dedup window TriggerFlow needs. Cluster mode provides multi-instance sharing. This is the correct production choice.
- **PostgreSQL**: ACID-compliant dedup with full query support (e.g., "how many duplicates did campaign X filter yesterday?"). Millisecond latency is acceptable because <0.1% of events reach the persistent tier.
- **DynamoDB**: Serverless, auto-scaling, with native TTL expiry. Ideal for cloud-native deployments where operational overhead must be minimized.

**Engineering judgment.** The in-memory implementation is a test double — it exists to enable the dedup module to function as a self-contained unit test without external dependencies. The `PersistentDedup` interface is the abstraction point: the `TriggerFlowEngineBuilder` accepts any implementation, and swapping to a Redis-backed store requires zero changes to the dedup module. This is the Strategy Pattern applied correctly.

---

## Summary

| Dimension | Choice | Key Trade-off | Risk Level |
|---|---|---|---|
| Deduplication | Three-tier Bloom/LRU/persistent | Complexity vs 1000× latency reduction | Low |
| JSON parsing | Hand-rolled recursive descent | Maintenance vs zero dependencies | Low |
| Rule engine | Sealed AST with weighted short-circuit | Simplicity vs expression language power | Low |
| Campaign registry | Copy-on-write volatile Map | O(N) writes vs lock-free reads at 1M/sec | Low |
| Delay scheduling | Hierarchical timing wheel | 100ms resolution vs O(1) insert at scale | Medium |
| Persistent dedup | In-memory ConcurrentHashMap | Testability vs crash durability | Medium |
