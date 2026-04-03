package triggerflow.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import triggerflow.action.ActionDispatcher;
import triggerflow.action.InMemoryDownstreamClient;
import triggerflow.action.RetryPolicy;
import triggerflow.action.ServiceRateLimiter;
import triggerflow.campaign.ABTestRouter;
import triggerflow.campaign.BudgetTracker;
import triggerflow.campaign.EventPrefilter;
import triggerflow.campaign.UserLimitTracker;
import triggerflow.dedup.BloomPrefilter;
import triggerflow.dedup.DeduplicationFilter;
import triggerflow.dedup.EventCache;
import triggerflow.dedup.InMemoryPersistentDedup;
import triggerflow.ingestion.ConsumerCoordinator;
import triggerflow.ingestion.InMemoryEventStream;
import triggerflow.rules.RuleEvaluator;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TriggerFlowEngineTest {

    private TriggerFlowEngine engine;
    private ProcessingMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new ProcessingMetrics();

        BloomPrefilter bloom = new BloomPrefilter(1_000, 0.01);
        EventCache cache = new EventCache(100);
        DeduplicationFilter dedupFilter = new DeduplicationFilter(
                bloom, cache, new InMemoryPersistentDedup(), Duration.ofMinutes(5));

        EventProcessor processor = new EventProcessor(
                dedupFilter,
                new EventPrefilter(),
                new RuleEvaluator(),
                new BudgetTracker(),
                new ABTestRouter(),
                new UserLimitTracker(),
                new ActionDispatcher(
                        new InMemoryDownstreamClient(),
                        new ServiceRateLimiter(Map.of()),
                        new RetryPolicy(0, Duration.ofMillis(10), 1.0, 0.0)
                ),
                metrics
        );

        InMemoryEventStream stream = new InMemoryEventStream(0);
        ConsumerCoordinator coordinator = new ConsumerCoordinator(
                List.of(stream), processor::process);

        engine = new TriggerFlowEngine(processor, coordinator, metrics);
    }

    @Test
    @DisplayName("engine is not running before start")
    void notRunning_beforeStart() {
        assertThat(engine.isRunning()).isFalse();
    }

    @Test
    @DisplayName("start sets running to true")
    void start_setsRunning() {
        engine.start();
        try {
            assertThat(engine.isRunning()).isTrue();
        } finally {
            engine.stop();
        }
    }

    @Test
    @DisplayName("stop sets running to false")
    void stop_setsNotRunning() {
        engine.start();
        engine.stop();

        assertThat(engine.isRunning()).isFalse();
    }

    @Test
    @DisplayName("metrics snapshot is available")
    void metricsSnapshot_available() {
        ProcessingMetrics.Snapshot snap = engine.metrics();

        assertThat(snap).isNotNull();
        assertThat(snap.eventsReceived()).isZero();
    }

    @Test
    @DisplayName("start and stop lifecycle can be repeated")
    void lifecycle_canRepeat() {
        engine.start();
        assertThat(engine.isRunning()).isTrue();
        engine.stop();
        assertThat(engine.isRunning()).isFalse();

        // Second cycle
        engine.start();
        assertThat(engine.isRunning()).isTrue();
        engine.stop();
        assertThat(engine.isRunning()).isFalse();
    }

    @Test
    @DisplayName("eventProcessor is accessible for direct submission")
    void eventProcessor_accessible() {
        assertThat(engine.eventProcessor()).isNotNull();
    }
}
