package triggerflow.dedup;

import triggerflow.common.EventKey;

import java.time.Instant;

/**
 * Interface for durable deduplication storage.
 *
 * <p>Implementations back the third tier of the dedup pipeline, providing
 * exact-match lookups that survive process restarts. Production implementations
 * would typically be backed by a database (Redis, PostgreSQL, DynamoDB).
 */
public interface PersistentDedup {

    /**
     * Checks whether the given event key has been previously recorded.
     *
     * @param key the event key to check
     * @return true if the key exists in persistent storage
     */
    boolean exists(EventKey key);

    /**
     * Records an event key with its processing timestamp.
     *
     * @param key         the event key to record
     * @param processedAt when the event was processed
     */
    void record(EventKey key, Instant processedAt);

    /**
     * Returns the number of recorded events.
     *
     * @return total records in the persistent store
     */
    int size();
}
