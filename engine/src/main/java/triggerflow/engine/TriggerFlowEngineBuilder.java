package triggerflow.engine;

import triggerflow.action.ActionDispatcher;
import triggerflow.action.DownstreamClient;
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
import triggerflow.dedup.PersistentDedup;
import triggerflow.ingestion.ConsumerCoordinator;
import triggerflow.ingestion.EventStream;
import triggerflow.rules.RuleEvaluator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builder pattern for assembling a fully-configured {@link TriggerFlowEngine}.
 *
 * <p>Provides sensible defaults for all components while allowing fine-grained
 * customization. Use {@link #defaults()} for a fully in-memory configuration
 * suitable for testing.
 *
 * <h3>Usage</h3>
 * <pre>
 *   TriggerFlowEngine engine = new TriggerFlowEngineBuilder()
 *       .bloomExpectedInsertions(100_000)
 *       .bloomFalsePositiveRate(0.001)
 *       .dedupCacheMaxSize(10_000)
 *       .dedupCacheTtl(Duration.ofMinutes(30))
 *       .streams(List.of(stream1, stream2))
 *       .build();
 * </pre>
 */
public class TriggerFlowEngineBuilder {

    // Bloom filter defaults
    private int bloomExpectedInsertions = 100_000;
    private double bloomFalsePositiveRate = 0.01;

    // Cache defaults
    private int dedupCacheMaxSize = 10_000;
    private Duration dedupCacheTtl = Duration.ofMinutes(30);

    // Persistent dedup (default: in-memory)
    private PersistentDedup persistentDedup;

    // Rate limiter
    private Map<String, ServiceRateLimiter.RateConfig> serviceRates = Map.of();

    // Downstream client
    private DownstreamClient downstreamClient;

    // Retry policy
    private RetryPolicy retryPolicy = RetryPolicy.defaults();

    // Event streams
    private List<EventStream> streams = new ArrayList<>();

    // Campaign components (can be pre-built or use defaults)
    private EventPrefilter eventPrefilter;
    private BudgetTracker budgetTracker;
    private ABTestRouter abTestRouter;
    private UserLimitTracker userLimitTracker;
    private RuleEvaluator ruleEvaluator;

    // --- Bloom filter configuration ---

    public TriggerFlowEngineBuilder bloomExpectedInsertions(int expectedInsertions) {
        this.bloomExpectedInsertions = expectedInsertions;
        return this;
    }

    public TriggerFlowEngineBuilder bloomFalsePositiveRate(double fpr) {
        this.bloomFalsePositiveRate = fpr;
        return this;
    }

    // --- Dedup cache configuration ---

    public TriggerFlowEngineBuilder dedupCacheMaxSize(int maxSize) {
        this.dedupCacheMaxSize = maxSize;
        return this;
    }

    public TriggerFlowEngineBuilder dedupCacheTtl(Duration ttl) {
        this.dedupCacheTtl = ttl;
        return this;
    }

    // --- Persistent dedup ---

    public TriggerFlowEngineBuilder persistentDedup(PersistentDedup persistentDedup) {
        this.persistentDedup = persistentDedup;
        return this;
    }

    // --- Rate limiter ---

    public TriggerFlowEngineBuilder serviceRates(Map<String, ServiceRateLimiter.RateConfig> rates) {
        this.serviceRates = Map.copyOf(rates);
        return this;
    }

    // --- Downstream client ---

    public TriggerFlowEngineBuilder downstreamClient(DownstreamClient client) {
        this.downstreamClient = client;
        return this;
    }

    // --- Retry policy ---

    public TriggerFlowEngineBuilder retryPolicy(RetryPolicy policy) {
        this.retryPolicy = policy;
        return this;
    }

    // --- Event streams ---

    public TriggerFlowEngineBuilder streams(List<EventStream> streams) {
        this.streams = new ArrayList<>(streams);
        return this;
    }

    public TriggerFlowEngineBuilder addStream(EventStream stream) {
        this.streams.add(stream);
        return this;
    }

    // --- Campaign components ---

    public TriggerFlowEngineBuilder eventPrefilter(EventPrefilter prefilter) {
        this.eventPrefilter = prefilter;
        return this;
    }

    public TriggerFlowEngineBuilder budgetTracker(BudgetTracker tracker) {
        this.budgetTracker = tracker;
        return this;
    }

    public TriggerFlowEngineBuilder abTestRouter(ABTestRouter router) {
        this.abTestRouter = router;
        return this;
    }

    public TriggerFlowEngineBuilder userLimitTracker(UserLimitTracker tracker) {
        this.userLimitTracker = tracker;
        return this;
    }

    public TriggerFlowEngineBuilder ruleEvaluator(RuleEvaluator evaluator) {
        this.ruleEvaluator = evaluator;
        return this;
    }

    // --- Build methods ---

    /**
     * Builds the full {@link TriggerFlowEngine} with all components wired together.
     *
     * @return a fully-configured engine ready to start
     */
    public TriggerFlowEngine build() {
        var metrics = new ProcessingMetrics();
        var processor = createProcessor(metrics);

        ConsumerCoordinator coordinator = new ConsumerCoordinator(
                List.copyOf(streams),
                processor::process
        );

        return new TriggerFlowEngine(processor, coordinator, metrics);
    }

    /**
     * Builds just the {@link EventProcessor} — useful for testing without the
     * full engine lifecycle.
     *
     * @return a configured event processor
     */
    public EventProcessor buildProcessor() {
        return createProcessor(new ProcessingMetrics());
    }

    /**
     * Builds just the {@link ProcessingMetrics} — useful for testing.
     *
     * @return a fresh metrics instance
     */
    public ProcessingMetrics buildMetrics() {
        return new ProcessingMetrics();
    }

    /**
     * Creates a fully-configured builder with in-memory implementations,
     * suitable for testing.
     *
     * @return a builder with sensible in-memory defaults
     */
    public static TriggerFlowEngineBuilder defaults() {
        return new TriggerFlowEngineBuilder()
                .bloomExpectedInsertions(10_000)
                .bloomFalsePositiveRate(0.01)
                .dedupCacheMaxSize(1_000)
                .dedupCacheTtl(Duration.ofMinutes(5))
                .downstreamClient(new InMemoryDownstreamClient())
                .retryPolicy(new RetryPolicy(0, Duration.ofMillis(10), 1.0, 0.0));
    }

    // --- Internal wiring ---

    private EventProcessor createProcessor(ProcessingMetrics metricsInstance) {
        // Dedup components
        BloomPrefilter bloom = new BloomPrefilter(bloomExpectedInsertions, bloomFalsePositiveRate);
        EventCache cache = new EventCache(dedupCacheMaxSize);
        PersistentDedup persistent = this.persistentDedup != null
                ? this.persistentDedup
                : new InMemoryPersistentDedup();
        DeduplicationFilter dedupFilter = new DeduplicationFilter(bloom, cache, persistent, dedupCacheTtl);

        // Rule evaluator
        RuleEvaluator evaluator = this.ruleEvaluator != null
                ? this.ruleEvaluator
                : new RuleEvaluator();

        // Campaign components
        EventPrefilter prefilter = this.eventPrefilter != null
                ? this.eventPrefilter
                : new EventPrefilter();
        BudgetTracker budget = this.budgetTracker != null
                ? this.budgetTracker
                : new BudgetTracker();
        ABTestRouter abRouter = this.abTestRouter != null
                ? this.abTestRouter
                : new ABTestRouter();
        UserLimitTracker userLimits = this.userLimitTracker != null
                ? this.userLimitTracker
                : new UserLimitTracker();

        // Action components
        ServiceRateLimiter rateLimiter = new ServiceRateLimiter(serviceRates);
        DownstreamClient client = this.downstreamClient != null
                ? this.downstreamClient
                : new InMemoryDownstreamClient();
        ActionDispatcher dispatcher = new ActionDispatcher(client, rateLimiter, retryPolicy);

        return new EventProcessor(
                dedupFilter,
                prefilter,
                evaluator,
                budget,
                abRouter,
                userLimits,
                dispatcher,
                metricsInstance
        );
    }
}
