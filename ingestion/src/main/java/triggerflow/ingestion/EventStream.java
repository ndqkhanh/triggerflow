package triggerflow.ingestion;

import triggerflow.common.EventKey;
import triggerflow.common.TriggerFlowEvent;

import java.time.Duration;
import java.util.List;

/**
 * Abstraction over a partitioned event stream (e.g. Kafka topic partition).
 *
 * <p>Follows the pattern from AgentForge's {@code EventBus}: a minimal interface
 * that can be backed by Kafka in production or an in-memory queue in tests.
 *
 * <p>Each {@code EventStream} represents a single partition. Multiple partitions
 * are coordinated by {@link ConsumerCoordinator}.
 */
public interface EventStream {

    /**
     * Polls for available events, blocking up to the given timeout.
     *
     * @param timeout maximum time to wait for events
     * @return a batch of events (may be empty, never null)
     */
    List<TriggerFlowEvent> poll(Duration timeout);

    /**
     * Commits the given event key, marking it as successfully processed.
     * Used for at-least-once delivery semantics.
     *
     * @param key the event key to commit
     */
    void commit(EventKey key);

    /**
     * Returns the partition number this stream represents.
     */
    int partition();
}
