package triggerflow.dedup;

import triggerflow.common.EventKey;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Three-tier deduplication coordinator.
 *
 * <h2>Dedup pipeline</h2>
 * <ol>
 *   <li><b>Bloom pre-filter</b> -- probabilistic check with guaranteed no false negatives.
 *       If the filter says "not seen", the event is definitely new, and we skip the
 *       more expensive exact lookups.</li>
 *   <li><b>In-memory cache</b> -- exact check in a time-bounded LRU cache. Covers the
 *       common case of recent duplicates arriving within the dedup window.</li>
 *   <li><b>Persistent store</b> -- exact check against durable storage for duplicates
 *       that survive cache eviction or process restarts.</li>
 * </ol>
 *
 * <p>After an event is processed, {@link #recordProcessed} updates all relevant tiers
 * so subsequent duplicates are caught.
 */
public class DeduplicationFilter {

    private final BloomPrefilter bloom;
    private final EventCache cache;
    private final PersistentDedup persistent;
    private final Duration cacheTtl;

    // Metrics
    private final AtomicLong newEvents = new AtomicLong();
    private final AtomicLong duplicatesCached = new AtomicLong();
    private final AtomicLong duplicatesPersistent = new AtomicLong();

    /**
     * Creates a DeduplicationFilter with the given tier implementations.
     *
     * @param bloom      Bloom pre-filter for fast probabilistic checks
     * @param cache      in-memory cache with TTL and LRU eviction
     * @param persistent durable deduplication store
     * @param cacheTtl   TTL for cache entries
     */
    public DeduplicationFilter(BloomPrefilter bloom, EventCache cache,
                               PersistentDedup persistent, Duration cacheTtl) {
        this.bloom = bloom;
        this.cache = cache;
        this.persistent = persistent;
        this.cacheTtl = cacheTtl;
    }

    /**
     * Checks whether the given event key is a duplicate.
     *
     * <p>The three tiers are checked in order. If the Bloom filter returns a
     * definite negative, the event is immediately classified as new without
     * consulting the cache or persistent store.
     *
     * @param key the event key to check
     * @return the deduplication result
     */
    public DeduplicationResult check(EventKey key) {
        // Tier 1: Bloom pre-filter (fast path for new events)
        if (!bloom.mightContain(key)) {
            newEvents.incrementAndGet();
            return DeduplicationResult.NEW_EVENT;
        }

        // Tier 2: In-memory cache
        if (cache.exists(key)) {
            duplicatesCached.incrementAndGet();
            return DeduplicationResult.DUPLICATE_CACHED;
        }

        // Tier 3: Persistent store
        if (persistent.exists(key)) {
            duplicatesPersistent.incrementAndGet();
            return DeduplicationResult.DUPLICATE_PERSISTENT;
        }

        // Bloom filter false positive -- event is actually new
        newEvents.incrementAndGet();
        return DeduplicationResult.NEW_EVENT;
    }

    /**
     * Records that an event has been successfully processed.
     *
     * <p>Always adds the key to the Bloom filter and cache. Only adds to the
     * persistent store if an action was actually triggered (to save storage
     * for events that matched rules).
     *
     * @param key             the event key that was processed
     * @param actionTriggered whether the event triggered an action
     */
    public void recordProcessed(EventKey key, boolean actionTriggered) {
        Instant now = Instant.now();

        // Always: add to Bloom filter and cache
        bloom.add(key);
        cache.put(key, now.toEpochMilli(), cacheTtl);

        // Only persist if an action was triggered
        if (actionTriggered) {
            persistent.record(key, now);
        }
    }

    // --- Metrics ---

    public long getNewEvents() { return newEvents.get(); }
    public long getDuplicatesCached() { return duplicatesCached.get(); }
    public long getDuplicatesPersistent() { return duplicatesPersistent.get(); }
}
