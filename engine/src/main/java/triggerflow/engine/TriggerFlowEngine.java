package triggerflow.engine;

import triggerflow.ingestion.ConsumerCoordinator;

import java.util.Objects;

/**
 * Main entry point for the TriggerFlow event processing engine.
 *
 * <p>Wires all components together and manages the lifecycle of event consumption.
 * The engine delegates event polling to a {@link ConsumerCoordinator} which feeds
 * events into an {@link EventProcessor} for full pipeline processing.
 *
 * <p>Use {@link TriggerFlowEngineBuilder} to assemble a fully-configured engine
 * with sensible defaults.
 */
public class TriggerFlowEngine {

    private final EventProcessor eventProcessor;
    private final ConsumerCoordinator coordinator;
    private final ProcessingMetrics metrics;
    private volatile boolean running;

    /**
     * @param eventProcessor the core event processing pipeline
     * @param coordinator    the consumer coordinator managing partition consumers
     * @param metrics        the processing metrics accumulator
     */
    public TriggerFlowEngine(
            EventProcessor eventProcessor,
            ConsumerCoordinator coordinator,
            ProcessingMetrics metrics
    ) {
        this.eventProcessor = Objects.requireNonNull(eventProcessor, "eventProcessor");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Starts the engine: begins consuming events from all configured streams.
     */
    public void start() {
        running = true;
        coordinator.start();
    }

    /**
     * Stops the engine: gracefully shuts down all partition consumers.
     */
    public void stop() {
        running = false;
        coordinator.stop();
    }

    /**
     * Returns whether the engine is currently running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns a point-in-time snapshot of processing metrics.
     */
    public ProcessingMetrics.Snapshot metrics() {
        return metrics.snapshot();
    }

    /**
     * Returns the event processor for direct event submission (useful for testing).
     */
    public EventProcessor eventProcessor() {
        return eventProcessor;
    }
}
