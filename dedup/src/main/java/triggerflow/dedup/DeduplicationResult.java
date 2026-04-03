package triggerflow.dedup;

/**
 * Result of a deduplication check, indicating whether an event is new or
 * a duplicate detected at a specific tier.
 *
 * <p>The three-tier deduplication pipeline checks in order:
 * <ol>
 *   <li>Bloom pre-filter (probabilistic, O(k) where k = hash count)</li>
 *   <li>In-memory cache (exact, O(1) via ConcurrentHashMap)</li>
 *   <li>Persistent store (exact, durable)</li>
 * </ol>
 */
public enum DeduplicationResult {

    /** The event has never been seen before (definite). */
    NEW_EVENT,

    /** The event was found in the in-memory cache (exact match). */
    DUPLICATE_CACHED,

    /** The event was found in the persistent store (exact match). */
    DUPLICATE_PERSISTENT
}
