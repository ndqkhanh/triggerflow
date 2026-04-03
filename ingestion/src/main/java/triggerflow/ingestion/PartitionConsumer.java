package triggerflow.ingestion;

import triggerflow.common.TriggerFlowEvent;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Consumes events from a single {@link EventStream} partition on a virtual thread.
 *
 * <p>The consumer runs a poll loop: it calls {@link EventStream#poll} at a
 * configurable interval, passes each event to the supplied processor, and
 * tracks the total number of events consumed.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@link #start()} — launches the virtual thread</li>
 *   <li>{@link #stop()} — sets the running flag to false and interrupts</li>
 * </ul>
 */
public class PartitionConsumer {

    private final EventStream stream;
    private final Consumer<TriggerFlowEvent> processor;
    private final Duration pollInterval;
    private final AtomicLong eventsConsumed = new AtomicLong();

    private volatile boolean running;
    private Thread consumerThread;

    /**
     * @param stream       the event stream (partition) to consume from
     * @param processor    callback invoked for each event
     * @param pollInterval how long to wait in each poll call
     */
    public PartitionConsumer(EventStream stream, Consumer<TriggerFlowEvent> processor, Duration pollInterval) {
        this.stream = stream;
        this.processor = processor;
        this.pollInterval = pollInterval;
    }

    /** Convenience constructor with a 100ms default poll interval. */
    public PartitionConsumer(EventStream stream, Consumer<TriggerFlowEvent> processor) {
        this(stream, processor, Duration.ofMillis(100));
    }

    /**
     * Launches the consumer on a virtual thread.
     */
    public void start() {
        running = true;
        consumerThread = Thread.ofVirtual()
                .name("partition-consumer-" + stream.partition())
                .start(this::pollLoop);
    }

    /**
     * Signals the consumer to stop and interrupts its thread.
     */
    public void stop() {
        running = false;
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
    }

    /**
     * Returns the total number of events consumed so far.
     */
    public long eventsConsumed() {
        return eventsConsumed.get();
    }

    /**
     * Returns true if the consumer is currently running.
     */
    public boolean isRunning() {
        return running && consumerThread != null && consumerThread.isAlive();
    }

    // -------------------------------------------------------------------------
    // Private
    // -------------------------------------------------------------------------

    private void pollLoop() {
        while (running) {
            try {
                List<TriggerFlowEvent> events = stream.poll(pollInterval);
                for (TriggerFlowEvent event : events) {
                    if (!running) break;
                    processor.accept(event);
                    eventsConsumed.incrementAndGet();
                }
                if (events.isEmpty()) {
                    Thread.sleep(pollInterval.toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
