package triggerflow.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessingMetricsTest {

    private ProcessingMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new ProcessingMetrics();
    }

    @Test
    @DisplayName("initial values are all zero")
    void initialValues_allZero() {
        ProcessingMetrics.Snapshot snap = metrics.snapshot();

        assertThat(snap.eventsReceived()).isZero();
        assertThat(snap.eventsProcessed()).isZero();
        assertThat(snap.duplicatesFiltered()).isZero();
        assertThat(snap.rulesEvaluated()).isZero();
        assertThat(snap.rulesMatched()).isZero();
        assertThat(snap.actionsTriggered()).isZero();
        assertThat(snap.actionsFailed()).isZero();
    }

    @Test
    @DisplayName("increment each counter independently")
    void incrementCounters_independently() {
        metrics.incrementEventsReceived();
        metrics.incrementEventsReceived();
        metrics.incrementEventsProcessed();
        metrics.incrementDuplicatesFiltered();
        metrics.incrementRulesEvaluated();
        metrics.incrementRulesEvaluated();
        metrics.incrementRulesEvaluated();
        metrics.incrementRulesMatched();
        metrics.incrementActionsTriggered();
        metrics.incrementActionsFailed();
        metrics.incrementActionsFailed();

        assertThat(metrics.getEventsReceived()).isEqualTo(2);
        assertThat(metrics.getEventsProcessed()).isEqualTo(1);
        assertThat(metrics.getDuplicatesFiltered()).isEqualTo(1);
        assertThat(metrics.getRulesEvaluated()).isEqualTo(3);
        assertThat(metrics.getRulesMatched()).isEqualTo(1);
        assertThat(metrics.getActionsTriggered()).isEqualTo(1);
        assertThat(metrics.getActionsFailed()).isEqualTo(2);
    }

    @Test
    @DisplayName("snapshot returns correct values after increments")
    void snapshot_returnsCorrectValues() {
        metrics.incrementEventsReceived();
        metrics.incrementEventsProcessed();
        metrics.incrementDuplicatesFiltered();
        metrics.incrementRulesEvaluated();
        metrics.incrementRulesMatched();
        metrics.incrementActionsTriggered();
        metrics.incrementActionsFailed();

        ProcessingMetrics.Snapshot snap = metrics.snapshot();

        assertThat(snap.eventsReceived()).isEqualTo(1);
        assertThat(snap.eventsProcessed()).isEqualTo(1);
        assertThat(snap.duplicatesFiltered()).isEqualTo(1);
        assertThat(snap.rulesEvaluated()).isEqualTo(1);
        assertThat(snap.rulesMatched()).isEqualTo(1);
        assertThat(snap.actionsTriggered()).isEqualTo(1);
        assertThat(snap.actionsFailed()).isEqualTo(1);
    }

    @Test
    @DisplayName("thread safety: concurrent increments produce correct total")
    void threadSafety_concurrentIncrements() throws InterruptedException {
        int threadCount = 10;
        int incrementsPerThread = 1_000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                for (int i = 0; i < incrementsPerThread; i++) {
                    metrics.incrementEventsReceived();
                    metrics.incrementEventsProcessed();
                    metrics.incrementActionsTriggered();
                }
                latch.countDown();
            });
        }

        latch.await();
        executor.shutdown();

        long expectedTotal = (long) threadCount * incrementsPerThread;
        assertThat(metrics.getEventsReceived()).isEqualTo(expectedTotal);
        assertThat(metrics.getEventsProcessed()).isEqualTo(expectedTotal);
        assertThat(metrics.getActionsTriggered()).isEqualTo(expectedTotal);
    }

    @Test
    @DisplayName("multiple snapshots reflect incremental changes")
    void multipleSnapshots_reflectChanges() {
        ProcessingMetrics.Snapshot snap1 = metrics.snapshot();
        assertThat(snap1.eventsReceived()).isZero();

        metrics.incrementEventsReceived();
        metrics.incrementEventsReceived();
        ProcessingMetrics.Snapshot snap2 = metrics.snapshot();
        assertThat(snap2.eventsReceived()).isEqualTo(2);

        metrics.incrementEventsReceived();
        ProcessingMetrics.Snapshot snap3 = metrics.snapshot();
        assertThat(snap3.eventsReceived()).isEqualTo(3);

        // Previous snapshots are immutable
        assertThat(snap1.eventsReceived()).isZero();
        assertThat(snap2.eventsReceived()).isEqualTo(2);
    }

    @Test
    @DisplayName("snapshot record equality works correctly")
    void snapshotEquality() {
        metrics.incrementEventsReceived();
        metrics.incrementEventsProcessed();

        ProcessingMetrics.Snapshot snap1 = metrics.snapshot();
        ProcessingMetrics.Snapshot snap2 = metrics.snapshot();

        assertThat(snap1).isEqualTo(snap2);
    }
}
