package triggerflow.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import triggerflow.action.ActionDispatcher;
import triggerflow.action.ActionRequest;
import triggerflow.action.InMemoryDownstreamClient;
import triggerflow.action.RetryPolicy;
import triggerflow.action.ServiceRateLimiter;
import triggerflow.campaign.ABTestConfig;
import triggerflow.campaign.ABTestRouter;
import triggerflow.campaign.ActionDefinition;
import triggerflow.campaign.BudgetConstraint;
import triggerflow.campaign.BudgetTracker;
import triggerflow.campaign.Campaign;
import triggerflow.campaign.CampaignStatus;
import triggerflow.campaign.EventPrefilter;
import triggerflow.campaign.UserLimitation;
import triggerflow.campaign.UserLimitTracker;
import triggerflow.common.TriggerFlowEvent;
import triggerflow.dedup.BloomPrefilter;
import triggerflow.dedup.DeduplicationFilter;
import triggerflow.dedup.EventCache;
import triggerflow.dedup.InMemoryPersistentDedup;
import triggerflow.ingestion.InMemoryEventStream;
import triggerflow.rules.ComparisonOp;
import triggerflow.rules.DataSource;
import triggerflow.rules.RuleEvaluator;
import triggerflow.rules.RuleNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end acceptance tests for the full TriggerFlow pipeline.
 *
 * <p>Uses in-memory implementations for all infrastructure: InMemoryEventStream,
 * InMemoryDownstreamClient, InMemoryPersistentDedup. Verifies the complete
 * event processing flow from ingestion through action dispatch.
 */
class EndToEndAcceptanceTest {

    private InMemoryDownstreamClient downstreamClient;
    private EventPrefilter eventPrefilter;
    private BudgetTracker budgetTracker;
    private ABTestRouter abTestRouter;
    private UserLimitTracker userLimitTracker;
    private ProcessingMetrics metrics;
    private EventProcessor processor;
    private DeduplicationFilter dedupFilter;

    @BeforeEach
    void setUp() {
        BloomPrefilter bloom = new BloomPrefilter(100_000, 0.001);
        EventCache cache = new EventCache(10_000);
        InMemoryPersistentDedup persistent = new InMemoryPersistentDedup();
        dedupFilter = new DeduplicationFilter(bloom, cache, persistent, Duration.ofMinutes(30));

        eventPrefilter = new EventPrefilter();
        budgetTracker = new BudgetTracker();
        abTestRouter = new ABTestRouter();
        userLimitTracker = new UserLimitTracker();
        downstreamClient = new InMemoryDownstreamClient();
        metrics = new ProcessingMetrics();

        ActionDispatcher dispatcher = new ActionDispatcher(
                downstreamClient,
                new ServiceRateLimiter(Map.of()),
                new RetryPolicy(0, Duration.ofMillis(10), 1.0, 0.0)
        );

        processor = new EventProcessor(
                dedupFilter, eventPrefilter, new RuleEvaluator(), budgetTracker,
                abTestRouter, userLimitTracker, dispatcher, metrics
        );
    }

    // --- Helpers ---

    private TriggerFlowEvent event(String eventId, String eventType, String userId, Map<String, Object> payload) {
        return new TriggerFlowEvent(eventId, eventType, userId, payload, Instant.now(), "test-service");
    }

    private Campaign campaign(String id, String eventType, RuleNode rule, long budget) {
        return new Campaign(
                id, "Campaign " + id, CampaignStatus.ACTIVE,
                eventType, rule,
                List.of(new ActionDefinition("SEND_MESSAGE", "notification-svc", Map.of("msg", "hello"))),
                new BudgetConstraint(budget, "USD"), ABTestConfig.disabled(),
                null, UserLimitation.unlimited(), null, null
        );
    }

    private Campaign campaign(String id, String eventType, RuleNode rule) {
        return campaign(id, eventType, rule, Long.MAX_VALUE);
    }

    private void registerCampaigns(List<Campaign> campaigns) {
        for (Campaign c : campaigns) {
            budgetTracker.initialize(c.campaignId(), c.budget().maxAmount());
        }
        eventPrefilter.rebuild(campaigns);
    }

    private void waitForActions() {
        try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // --- Acceptance Tests ---

    @Test
    @DisplayName("full pipeline: event matches campaign, action dispatched to downstream")
    void fullPipeline_eventMatchesCampaign_actionDispatched() {
        RuleNode rule = new RuleNode.ConditionNode("country", ComparisonOp.EQ, "VN", DataSource.MEMORY);
        Campaign c = campaign("camp-1", "RIDE_COMPLETED", rule);
        registerCampaigns(List.of(c));

        processor.process(event("e1", "RIDE_COMPLETED", "user-1", Map.of("country", "VN")));
        waitForActions();

        assertThat(downstreamClient.getReceivedRequests()).hasSize(1);
        ActionRequest req = downstreamClient.getReceivedRequests().getFirst();
        assertThat(req.campaignId()).isEqualTo("camp-1");
        assertThat(req.userId()).isEqualTo("user-1");
        assertThat(metrics.getEventsProcessed()).isEqualTo(1);
        assertThat(metrics.getActionsTriggered()).isEqualTo(1);
    }

    @Test
    @DisplayName("exactly-once: publish same event 10 times, only 1 action dispatched")
    void exactlyOnce_tenDuplicates_oneAction() {
        RuleNode rule = new RuleNode.ConditionNode("country", ComparisonOp.EQ, "VN", DataSource.MEMORY);
        Campaign c = campaign("camp-dedup", "RIDE_COMPLETED", rule);
        registerCampaigns(List.of(c));

        TriggerFlowEvent duplicateEvent = event("e-dup", "RIDE_COMPLETED", "user-1", Map.of("country", "VN"));
        for (int i = 0; i < 10; i++) {
            processor.process(duplicateEvent);
        }
        waitForActions();

        assertThat(downstreamClient.getReceivedRequests()).hasSize(1);
        assertThat(metrics.getEventsReceived()).isEqualTo(10);
        assertThat(metrics.getDuplicatesFiltered()).isEqualTo(9);
        assertThat(metrics.getEventsProcessed()).isEqualTo(1);
    }

    @Test
    @DisplayName("prefilter: 100 campaigns but event matches only 2 — verify only 2 rule evaluations")
    void prefilter_100campaigns_only2match() {
        List<Campaign> campaigns = new ArrayList<>();

        // 2 campaigns for RIDE_COMPLETED
        RuleNode passingRule = new RuleNode.ConditionNode("country", ComparisonOp.EQ, "VN", DataSource.MEMORY);
        campaigns.add(campaign("camp-ride-1", "RIDE_COMPLETED", passingRule));
        campaigns.add(campaign("camp-ride-2", "RIDE_COMPLETED", passingRule));

        // 98 campaigns for other event types
        for (int i = 0; i < 98; i++) {
            campaigns.add(campaign("camp-other-" + i, "ORDER_PLACED", passingRule));
        }

        registerCampaigns(campaigns);

        processor.process(event("e-pf", "RIDE_COMPLETED", "user-1", Map.of("country", "VN")));
        waitForActions();

        // Only 2 rule evaluations because prefilter narrows to RIDE_COMPLETED campaigns
        assertThat(metrics.getRulesEvaluated()).isEqualTo(2);
        assertThat(metrics.getRulesMatched()).isEqualTo(2);
        assertThat(downstreamClient.getReceivedRequests()).hasSize(2);
    }

    @Test
    @DisplayName("weighted eval: AND(MEMORY_fail, EXTERNAL) — external never evaluated")
    void weightedEval_shortCircuit() {
        RuleNode andRule = new RuleNode.AndNode(List.of(
                new RuleNode.ConditionNode("country", ComparisonOp.EQ, "US", DataSource.MEMORY),
                new RuleNode.ConditionNode("external.fraud_score", ComparisonOp.LT, 50, DataSource.EXTERNAL_SERVICE)
        ));

        Campaign c = campaign("camp-sc", "RIDE_COMPLETED", andRule);
        registerCampaigns(List.of(c));

        processor.process(event("e-sc", "RIDE_COMPLETED", "user-1", Map.of("country", "VN")));
        waitForActions();

        // Rule was evaluated (1 campaign), but it didn't match
        assertThat(metrics.getRulesEvaluated()).isEqualTo(1);
        assertThat(metrics.getRulesMatched()).isZero();
        assertThat(metrics.getActionsTriggered()).isZero();
    }

    @Test
    @DisplayName("budget exhaustion: budget=3, send 5 matching events — only 3 actions")
    void budgetExhaustion_onlyBudgetCountActions() {
        RuleNode rule = new RuleNode.ConditionNode("country", ComparisonOp.EQ, "VN", DataSource.MEMORY);
        Campaign c = campaign("camp-budget", "RIDE_COMPLETED", rule, 3);
        registerCampaigns(List.of(c));

        for (int i = 0; i < 5; i++) {
            processor.process(event("e-budget-" + i, "RIDE_COMPLETED", "user-" + i, Map.of("country", "VN")));
        }
        waitForActions();

        // Only 3 actions should be dispatched (budget = 3)
        assertThat(downstreamClient.getReceivedRequests()).hasSize(3);
        assertThat(metrics.getActionsTriggered()).isEqualTo(3);
    }

    @Test
    @DisplayName("A/B test determinism: same user always gets same variant")
    void abTestDeterminism_sameVariant() {
        String userId = "user-ab-test";
        ABTestConfig abConfig = new ABTestConfig(Map.of("control", 0.5, "treatment", 0.5));

        String firstVariant = abTestRouter.getVariant(userId, abConfig);

        // Verify determinism — same user always gets same variant
        for (int i = 0; i < 100; i++) {
            String variant = abTestRouter.getVariant(userId, abConfig);
            assertThat(variant).isEqualTo(firstVariant);
        }
    }

    @Test
    @DisplayName("multi-campaign: 3 campaigns match event — 3 separate actions dispatched")
    void multiCampaign_allMatch_allActionsDispatched() {
        RuleNode rule = new RuleNode.ConditionNode("country", ComparisonOp.EQ, "VN", DataSource.MEMORY);
        Campaign c1 = campaign("camp-multi-1", "RIDE_COMPLETED", rule);
        Campaign c2 = campaign("camp-multi-2", "RIDE_COMPLETED", rule);
        Campaign c3 = campaign("camp-multi-3", "RIDE_COMPLETED", rule);
        registerCampaigns(List.of(c1, c2, c3));

        processor.process(event("e-multi", "RIDE_COMPLETED", "user-1", Map.of("country", "VN")));
        waitForActions();

        assertThat(downstreamClient.getReceivedRequests()).hasSize(3);
        assertThat(metrics.getRulesEvaluated()).isEqualTo(3);
        assertThat(metrics.getRulesMatched()).isEqualTo(3);
        assertThat(metrics.getActionsTriggered()).isEqualTo(3);

        Set<String> campaignIds = downstreamClient.getReceivedRequests().stream()
                .map(ActionRequest::campaignId)
                .collect(Collectors.toSet());
        assertThat(campaignIds).containsExactlyInAnyOrder("camp-multi-1", "camp-multi-2", "camp-multi-3");
    }

    @Test
    @DisplayName("builder creates working engine with in-memory defaults")
    void builder_createsWorkingEngine() {
        TriggerFlowEngine engine = TriggerFlowEngineBuilder.defaults()
                .addStream(new InMemoryEventStream(0))
                .build();

        assertThat(engine).isNotNull();
        assertThat(engine.isRunning()).isFalse();

        engine.start();
        assertThat(engine.isRunning()).isTrue();

        ProcessingMetrics.Snapshot snap = engine.metrics();
        assertThat(snap.eventsReceived()).isZero();

        engine.stop();
        assertThat(engine.isRunning()).isFalse();
    }
}
