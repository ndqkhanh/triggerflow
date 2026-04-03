package triggerflow.common;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe pipeline metrics using lock-free atomic counters.
 *
 * <p>Each counter is backed by an {@link AtomicLong} to support high-throughput
 * concurrent increments without synchronization overhead. The {@link #snapshot()}
 * method captures a consistent-enough view of all counters at a point in time
 * (individual reads are atomic, but the aggregate is not a global snapshot).
 */
public final class TriggerFlowMetrics {

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

    // --- raw reads (for internal use) ---

    public long getEventsReceived() { return eventsReceived.get(); }
    public long getEventsProcessed() { return eventsProcessed.get(); }
    public long getDuplicatesFiltered() { return duplicatesFiltered.get(); }
    public long getRulesEvaluated() { return rulesEvaluated.get(); }
    public long getRulesMatched() { return rulesMatched.get(); }
    public long getActionsTriggered() { return actionsTriggered.get(); }
    public long getActionsFailed() { return actionsFailed.get(); }

    /**
     * Captures a point-in-time snapshot of all metric counters.
     *
     * <p>Each counter read is individually atomic, but the aggregate record is
     * not a globally consistent snapshot. For dashboards and logging this is
     * acceptable; for billing-critical accuracy use a synchronized wrapper.
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
}
