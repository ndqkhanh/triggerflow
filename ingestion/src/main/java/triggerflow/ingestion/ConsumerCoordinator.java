package triggerflow.ingestion;

import triggerflow.common.TriggerFlowEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Coordinates multiple {@link PartitionConsumer}s — one per {@link EventStream}.
 *
 * <p>On {@link #start()}, creates a {@code PartitionConsumer} for each stream
 * and starts them all on virtual threads. On {@link #stop()}, gracefully shuts
 * down every consumer.
 */
public class ConsumerCoordinator {

    private final List<EventStream> streams;
    private final Consumer<TriggerFlowEvent> processor;
    private final List<PartitionConsumer> consumers;

    /**
     * @param streams   the event streams (partitions) to consume from
     * @param processor callback invoked for each event across all partitions
     */
    public ConsumerCoordinator(List<EventStream> streams, Consumer<TriggerFlowEvent> processor) {
        this.streams = List.copyOf(streams);
        this.processor = processor;
        this.consumers = new ArrayList<>();
    }

    /**
     * Creates and starts a consumer for each stream.
     */
    public void start() {
        for (EventStream stream : streams) {
            PartitionConsumer consumer = new PartitionConsumer(stream, processor);
            consumers.add(consumer);
            consumer.start();
        }
    }

    /**
     * Stops all consumers gracefully.
     */
    public void stop() {
        for (PartitionConsumer consumer : consumers) {
            consumer.stop();
        }
    }

    /**
     * Returns the sum of events consumed across all partition consumers.
     */
    public long totalEventsConsumed() {
        long total = 0;
        for (PartitionConsumer consumer : consumers) {
            total += consumer.eventsConsumed();
        }
        return total;
    }

    /**
     * Returns an unmodifiable view of the active consumers.
     */
    public List<PartitionConsumer> consumers() {
        return Collections.unmodifiableList(consumers);
    }
}
