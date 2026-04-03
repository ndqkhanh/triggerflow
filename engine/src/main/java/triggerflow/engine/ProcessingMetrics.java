package triggerflow.engine;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe processing metrics using lock-free atomic counters.
 *
 * <p>Tracks event counts through each stage of the pipeline: received,
 * deduplicated, rule-evaluated, matched, action-dispatched, and failed.
 * The {@link #snapshot()} method captures a point-in-time view of all counters.
 */
public final class ProcessingMetrics {

    private final AtomicLong eventsReceived = new AtomicLong();
    private final AtomicLong eventsProcessed = new AtomicLong();
    private final AtomicLong duplicatesFiltered = new AtomicLong();
    private final AtomicLong rulesEvaluated = new AtomicLong();
    private final AtomicLong rulesMatched = new AtomicLong();
    private final AtomicLong actionsTriggered = new AtomicLong();
    private final AtomicLong actionsFailed = new AtomicLong();

    // --- increment methods ---

    public void incrementEventsReceived() { eventsReceived.incrementAndGet(); }
    public void incrementEventsProcessed() { eventsProcessed.incrementAndGet(); }
    public void incrementDuplicatesFiltered() { duplicatesFiltered.incrementAndGet(); }
    public void incrementRulesEvaluated() { rulesEvaluated.incrementAndGet(); }
    public void incrementRulesMatched() { rulesMatched.incrementAndGet(); }
    public void incrementActionsTriggered() { actionsTriggered.incrementAndGet(); }
    public void incrementActionsFailed() { actionsFailed.incrementAndGet(); }

    // --- raw reads ---

    public long getEventsReceived() { return eventsReceived.get(); }
    public long getEventsProcessed() { return eventsProcessed.get(); }
    public long getDuplicatesFiltered() { return duplicatesFiltered.get(); }
    public long getRulesEvaluated() { return rulesEvaluated.get(); }
    public long getRulesMatched() { return rulesMatched.get(); }
    public long getActionsTriggered() { return actionsTriggered.get(); }
    public long getActionsFailed() { return actionsFailed.get(); }

    /**
     * Immutable snapshot of all metric counter values at a point in time.
     */
    public record Snapshot(
            long eventsReceived,
            long eventsProcessed,
            long duplicatesFiltered,
            long rulesEvaluated,
            long rulesMatched,
            long actionsTriggered,
            long actionsFailed
    ) {}

    /**
     * Captures a point-in-time snapshot of all metric counters.
     *
     * <p>Each counter read is individually atomic, but the aggregate record is
     * not a globally consistent snapshot. For dashboards and logging this is
     * acceptable.
     *
     * @return immutable record of current counter values
     */
    public Snapshot snapshot() {
        return new Snapshot(
                eventsReceived.get(),
                eventsProcessed.get(),
                duplicatesFiltered.get(),
                rulesEvaluated.get(),
                rulesMatched.get(),
                actionsTriggered.get(),
                actionsFailed.get()
        );
    }
}
