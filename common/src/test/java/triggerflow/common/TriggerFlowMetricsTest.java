package triggerflow.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class TriggerFlowMetricsTest {

    @Test
    @DisplayName("Initial values are all zero")
    void initialValues_allZero() {
        var metrics = new TriggerFlowMetrics();
        var snapshot = metrics.snapshot();

        assertThat(snapshot.eventsReceived()).isZero();
        assertThat(snapshot.eventsProcessed()).isZero();
        assertThat(snapshot.duplicatesFiltered()).isZero();
        assertThat(snapshot.rulesEvaluated()).isZero();
        assertThat(snapshot.rulesMatched()).isZero();
        assertThat(snapshot.actionsTriggered()).isZero();
        assertThat(snapshot.actionsFailed()).isZero();
    }

    @Test
    @DisplayName("Increment updates correct counter")
    void increment_updatesCorrectCounter() {
        var metrics = new TriggerFlowMetrics();

        metrics.incrementEventsReceived();
        metrics.incrementEventsReceived();
        metrics.incrementEventsProcessed();
        metrics.incrementDuplicatesFiltered();
        metrics.incrementRulesEvaluated();
        metrics.incrementRulesMatched();
        metrics.incrementActionsTriggered();
        metrics.incrementActionsFailed();

        var snapshot = metrics.snapshot();
        assertThat(snapshot.eventsReceived()).isEqualTo(2);
        assertThat(snapshot.eventsProcessed()).isEqualTo(1);
        assertThat(snapshot.duplicatesFiltered()).isEqualTo(1);
        assertThat(snapshot.rulesEvaluated()).isEqualTo(1);
        assertThat(snapshot.rulesMatched()).isEqualTo(1);
        assertThat(snapshot.actionsTriggered()).isEqualTo(1);
        assertThat(snapshot.actionsFailed()).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrent increments are thread-safe")
    void concurrentIncrements_threadSafe() throws Exception {
        var metrics = new TriggerFlowMetrics();
        int threads = 8;
        int incrementsPerThread = 10_000;
        var latch = new CountDownLatch(threads);

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                executor.submit(() -> {
                    for (int i = 0; i < incrementsPerThread; i++) {
                        metrics.incrementEventsReceived();
                        metrics.incrementEventsProcessed();
                    }
                    latch.countDown();
                });
            }
            latch.await();
        }

        long expected = (long) threads * incrementsPerThread;
        assertThat(metrics.getEventsReceived()).isEqualTo(expected);
        assertThat(metrics.getEventsProcessed()).isEqualTo(expected);
    }

    @Test
    @DisplayName("Snapshot returns consistent view of counters")
    void snapshot_returnsConsistentView() {
        var metrics = new TriggerFlowMetrics();
        for (int i = 0; i < 100; i++) {
            metrics.incrementEventsReceived();
        }

        var snapshot = metrics.snapshot();

        // Snapshot is a frozen record, further increments don't change it
        metrics.incrementEventsReceived();
        assertThat(snapshot.eventsReceived()).isEqualTo(100);
        assertThat(metrics.getEventsReceived()).isEqualTo(101);
    }

    @Test
    @DisplayName("Raw getters reflect current values")
    void rawGetters_reflectCurrentValues() {
        var metrics = new TriggerFlowMetrics();
        metrics.incrementRulesMatched();
        metrics.incrementRulesMatched();
        metrics.incrementRulesMatched();

        assertThat(metrics.getRulesMatched()).isEqualTo(3);
    }

    @Test
    @DisplayName("Multiple snapshots are independent")
    void multipleSnapshots_independent() {
        var metrics = new TriggerFlowMetrics();
        metrics.incrementActionsFailed();
        var snap1 = metrics.snapshot();

        metrics.incrementActionsFailed();
        metrics.incrementActionsFailed();
        var snap2 = metrics.snapshot();

        assertThat(snap1.actionsFailed()).isEqualTo(1);
        assertThat(snap2.actionsFailed()).isEqualTo(3);
    }
}
