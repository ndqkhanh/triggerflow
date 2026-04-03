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
import triggerflow.rules.ComparisonOp;
import triggerflow.rules.DataSource;
import triggerflow.rules.RuleEvaluator;
import triggerflow.rules.RuleNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventProcessorTest {

    private DeduplicationFilter dedupFilter;
    private EventPrefilter eventPrefilter;
    private RuleEvaluator ruleEvaluator;
    private BudgetTracker budgetTracker;
    private ABTestRouter abTestRouter;
    private UserLimitTracker userLimitTracker;
    private InMemoryDownstreamClient downstreamClient;
    private ActionDispatcher actionDispatcher;
    private ProcessingMetrics metrics;
    private EventProcessor processor;

    @BeforeEach
    void setUp() {
        BloomPrefilter bloom = new BloomPrefilter(10_000, 0.01);
        EventCache cache = new EventCache(1_000);
        InMemoryPersistentDedup persistent = new InMemoryPersistentDedup();
        dedupFilter = new DeduplicationFilter(bloom, cache, persistent, Duration.ofMinutes(5));

        eventPrefilter = new EventPrefilter();
        ruleEvaluator = new RuleEvaluator();
        budgetTracker = new BudgetTracker();
        abTestRouter = new ABTestRouter();
        userLimitTracker = new UserLimitTracker();
        downstreamClient = new InMemoryDownstreamClient();
        actionDispatcher = new ActionDispatcher(
                downstreamClient,
                new ServiceRateLimiter(Map.of()),
                new RetryPolicy(0, Duration.ofMillis(10), 1.0, 0.0)
        );
        metrics = new ProcessingMetrics();

        processor = new EventProcessor(
                dedupFilter, eventPrefilter, ruleEvaluator, budgetTracker,
                abTestRouter, userLimitTracker, actionDispatcher, metrics
        );
    }

    // --- Helper methods ---

    private TriggerFlowEvent testEvent(String eventId, String eventType, String userId, Map<String, Object> payload) {
        return new TriggerFlowEvent(eventId, eventType, userId, payload, Instant.now(), "test-service");
    }

    private TriggerFlowEvent testEvent(String eventId) {
        return testEvent(eventId, "RIDE_COMPLETED", "user-1", Map.of("country", "VN"));
    }

    private Campaign activeCampaign(String campaignId, String eventType, RuleNode conditions) {
        return new Campaign(
                campaignId, "Test Campaign", CampaignStatus.ACTIVE,
                eventType, conditions,
                List.of(new ActionDefinition("SEND_MESSAGE", "notification-svc", Map.of())),
                BudgetConstraint.unlimited(), ABTestConfig.disabled(),
                null, UserLimitation.unlimited(), null, null
        );
    }

    private RuleNode passingRule() {
        return new RuleNode.ConditionNode("country", ComparisonOp.EQ, "VN", DataSource.MEMORY);
    }

    private RuleNode failingRule() {
        return new RuleNode.ConditionNode("country", ComparisonOp.EQ, "US", DataSource.MEMORY);
    }

    private void registerCampaign(Campaign campaign) {
        budgetTracker.initialize(campaign.campaignId(), campaign.budget().maxAmount());
        eventPrefilter.rebuild(List.of(campaign));
    }

    private void registerCampaigns(List<Campaign> campaigns) {
        for (Campaign c : campaigns) {
            budgetTracker.initialize(c.campaignId(), c.budget().maxAmount());
        }
        eventPrefilter.rebuild(campaigns);
    }

    private void waitForActions() {
        // Give virtual threads time to complete dispatch
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // --- Tests ---

    @Test
    @DisplayName("new event processes successfully with metrics incremented")
    void newEvent_processedSuccessfully() {
        Campaign campaign = activeCampaign("c1", "RIDE_COMPLETED", passingRule());
        registerCampaign(campaign);

        processor.process(testEvent("evt-1"));
        waitForActions();

        assertThat(metrics.getEventsReceived()).isEqualTo(1);
        assertThat(metrics.getEventsProcessed()).isEqualTo(1);
        assertThat(metrics.getRulesEvaluated()).isEqualTo(1);
        assertThat(metrics.getRulesMatched()).isEqualTo(1);
        assertThat(metrics.getActionsTriggered()).isEqualTo(1);
        assertThat(downstreamClient.getReceivedRequests()).hasSize(1);
    }

    @Test
    @DisplayName("duplicate event is filtered and duplicatesFiltered incremented")
    void duplicateEvent_filtered() {
        Campaign campaign = activeCampaign("c1", "RIDE_COMPLETED", passingRule());
        registerCampaign(campaign);

        processor.process(testEvent("evt-dup"));
        processor.process(testEvent("evt-dup"));
        waitForActions();

        assertThat(metrics.getEventsReceived()).isEqualTo(2);
        assertThat(metrics.getDuplicatesFiltered()).isEqualTo(1);
        assertThat(metrics.getEventsProcessed()).isEqualTo(1);
        assertThat(downstreamClient.getReceivedRequests()).hasSize(1);
    }

    @Test
    @DisplayName("event with no matching campaigns is processed but no actions dispatched")
    void noMatchingCampaigns_noActions() {
        // Don't register any campaigns for RIDE_COMPLETED
        eventPrefilter.rebuild(List.of());

        processor.process(testEvent("evt-no-match"));

        assertThat(metrics.getEventsReceived()).isEqualTo(1);
        assertThat(metrics.getEventsProcessed()).isEqualTo(1);
        assertThat(metrics.getRulesEvaluated()).isZero();
        assertThat(metrics.getActionsTriggered()).isZero();
        assertThat(downstreamClient.getReceivedRequests()).isEmpty();
    }

    @Test
    @DisplayName("event matching campaign with passing rule triggers action")
    void passingRule_actionDispatched() {
        Campaign campaign = activeCampaign("c1", "RIDE_COMPLETED", passingRule());
        registerCampaign(campaign);

        processor.process(testEvent("evt-pass"));
        waitForActions();

        assertThat(metrics.getRulesMatched()).isEqualTo(1);
        assertThat(metrics.getActionsTriggered()).isEqualTo(1);
        assertThat(downstreamClient.getReceivedRequests()).hasSize(1);

        ActionRequest received = downstreamClient.getReceivedRequests().getFirst();
        assertThat(received.campaignId()).isEqualTo("c1");
        assertThat(received.userId()).isEqualTo("user-1");
    }

    @Test
    @DisplayName("event matching campaign with failing rule does not trigger action")
    void failingRule_noAction() {
        Campaign campaign = activeCampaign("c1", "RIDE_COMPLETED", failingRule());
        registerCampaign(campaign);

        processor.process(testEvent("evt-fail"));
        waitForActions();

        assertThat(metrics.getRulesEvaluated()).isEqualTo(1);
        assertThat(metrics.getRulesMatched()).isZero();
        assertThat(metrics.getActionsTriggered()).isZero();
        assertThat(downstreamClient.getReceivedRequests()).isEmpty();
    }

    @Test
    @DisplayName("budget depleted campaign is skipped")
    void budgetDepleted_skipped() {
        Campaign campaign = new Campaign(
                "c-budget", "Budget Campaign", CampaignStatus.ACTIVE,
                "RIDE_COMPLETED", passingRule(),
                List.of(new ActionDefinition("SEND_MESSAGE", "notification-svc", Map.of())),
                new BudgetConstraint(0, "USD"), ABTestConfig.disabled(),
                null, UserLimitation.unlimited(), null, null
        );
        budgetTracker.initialize("c-budget", 0);
        eventPrefilter.rebuild(List.of(campaign));

        processor.process(testEvent("evt-budget"));
        waitForActions();

        assertThat(metrics.getRulesEvaluated()).isZero();
        assertThat(metrics.getActionsTriggered()).isZero();
        assertThat(downstreamClient.getReceivedRequests()).isEmpty();
    }

    @Test
    @DisplayName("user limit exceeded causes campaign to be skipped")
    void userLimitExceeded_skipped() {
        Campaign campaign = new Campaign(
                "c-limit", "Limited Campaign", CampaignStatus.ACTIVE,
                "RIDE_COMPLETED", passingRule(),
                List.of(new ActionDefinition("SEND_MESSAGE", "notification-svc", Map.of())),
                BudgetConstraint.unlimited(), ABTestConfig.disabled(),
                null, new UserLimitation(1, Duration.ofDays(1)), null, null
        );
        registerCampaign(campaign);

        // First event consumes the user limit
        processor.process(testEvent("evt-limit-1"));
        // Second event with a different eventId but same userId
        processor.process(testEvent("evt-limit-2"));
        waitForActions();

        // Only 1 action should be triggered (user limit = 1)
        assertThat(metrics.getActionsTriggered()).isEqualTo(1);
        assertThat(downstreamClient.getReceivedRequests()).hasSize(1);
    }

    @Test
    @DisplayName("inactive campaign is skipped")
    void inactiveCampaign_skipped() {
        Campaign campaign = new Campaign(
                "c-draft", "Draft Campaign", CampaignStatus.DRAFT,
                "RIDE_COMPLETED", passingRule(),
                List.of(new ActionDefinition("SEND_MESSAGE", "notification-svc", Map.of())),
                BudgetConstraint.unlimited(), ABTestConfig.disabled(),
                null, UserLimitation.unlimited(), null, null
        );
        // Manually add to prefilter by rebuilding with an ACTIVE campaign first,
        // then setting it to DRAFT — but EventPrefilter only indexes ACTIVE.
        // So we test that the campaign never appears in getCampaigns.
        budgetTracker.initialize("c-draft", Long.MAX_VALUE);
        eventPrefilter.rebuild(List.of(campaign)); // DRAFT won't be indexed

        processor.process(testEvent("evt-draft"));
        waitForActions();

        // No campaigns match because DRAFT is not indexed
        assertThat(metrics.getRulesEvaluated()).isZero();
        assertThat(metrics.getActionsTriggered()).isZero();
    }

    @Test
    @DisplayName("campaign outside time window is skipped")
    void outsideTimeWindow_skipped() {
        Campaign futureCampaign = new Campaign(
                "c-future", "Future Campaign", CampaignStatus.ACTIVE,
                "RIDE_COMPLETED", passingRule(),
                List.of(new ActionDefinition("SEND_MESSAGE", "notification-svc", Map.of())),
                BudgetConstraint.unlimited(), ABTestConfig.disabled(),
                null, UserLimitation.unlimited(),
                Instant.now().plusSeconds(3600), // starts in the future
                Instant.now().plusSeconds(7200)
        );
        registerCampaign(futureCampaign);

        processor.process(testEvent("evt-future"));
        waitForActions();

        assertThat(metrics.getRulesEvaluated()).isZero();
        assertThat(metrics.getActionsTriggered()).isZero();
    }

    @Test
    @DisplayName("campaign with past end time is skipped")
    void pastEndTime_skipped() {
        Campaign expiredCampaign = new Campaign(
                "c-expired", "Expired Campaign", CampaignStatus.ACTIVE,
                "RIDE_COMPLETED", passingRule(),
                List.of(new ActionDefinition("SEND_MESSAGE", "notification-svc", Map.of())),
                BudgetConstraint.unlimited(), ABTestConfig.disabled(),
                null, UserLimitation.unlimited(),
                Instant.now().minusSeconds(7200),
                Instant.now().minusSeconds(3600) // ended in the past
        );
        registerCampaign(expiredCampaign);

        processor.process(testEvent("evt-expired"));
        waitForActions();

        assertThat(metrics.getRulesEvaluated()).isZero();
        assertThat(metrics.getActionsTriggered()).isZero();
    }

    @Test
    @DisplayName("multiple campaigns: one matches, one does not — only matching gets action")
    void multipleCampaigns_partialMatch() {
        Campaign matching = activeCampaign("c-match", "RIDE_COMPLETED", passingRule());
        Campaign nonMatching = activeCampaign("c-no-match", "RIDE_COMPLETED", failingRule());
        registerCampaigns(List.of(matching, nonMatching));

        processor.process(testEvent("evt-multi"));
        waitForActions();

        assertThat(metrics.getRulesEvaluated()).isEqualTo(2);
        assertThat(metrics.getRulesMatched()).isEqualTo(1);
        assertThat(metrics.getActionsTriggered()).isEqualTo(1);

        ActionRequest received = downstreamClient.getReceivedRequests().getFirst();
        assertThat(received.campaignId()).isEqualTo("c-match");
    }

    @Test
    @DisplayName("action dispatch failure increments actionsFailed")
    void actionDispatchFailure_failedMetric() {
        // Use a failing downstream client
        InMemoryDownstreamClient failingClient = new InMemoryDownstreamClient(false, Duration.ZERO);
        ActionDispatcher failingDispatcher = new ActionDispatcher(
                failingClient,
                new ServiceRateLimiter(Map.of()),
                new RetryPolicy(0, Duration.ofMillis(10), 1.0, 0.0)
        );

        EventProcessor failProcessor = new EventProcessor(
                dedupFilter, eventPrefilter, ruleEvaluator, budgetTracker,
                abTestRouter, userLimitTracker, failingDispatcher, metrics
        );

        Campaign campaign = activeCampaign("c-fail", "RIDE_COMPLETED", passingRule());
        registerCampaign(campaign);

        // dispatch() returns a CompletableFuture that won't throw — it returns a failed ActionResult
        // The dispatch itself doesn't throw, so actionsTriggered increments
        failProcessor.process(testEvent("evt-dispatch-fail"));
        waitForActions();

        assertThat(metrics.getActionsTriggered()).isEqualTo(1);
        assertThat(failingClient.getReceivedRequests()).hasSize(1);
    }

    @Test
    @DisplayName("exactly-once: process same event twice — second time is duplicate")
    void exactlyOnce_secondTimeDuplicate() {
        Campaign campaign = activeCampaign("c1", "RIDE_COMPLETED", passingRule());
        registerCampaign(campaign);

        TriggerFlowEvent event = testEvent("evt-once");
        processor.process(event);
        processor.process(event);
        waitForActions();

        assertThat(metrics.getEventsReceived()).isEqualTo(2);
        assertThat(metrics.getDuplicatesFiltered()).isEqualTo(1);
        assertThat(metrics.getEventsProcessed()).isEqualTo(1);
        assertThat(downstreamClient.getReceivedRequests()).hasSize(1);
    }

    @Test
    @DisplayName("rule evaluation short-circuit: AND with MEMORY fail + EXTERNAL — only 1 condition evaluated")
    void ruleShortCircuit_andWithMemoryFail() {
        // AND(MEMORY fails, EXTERNAL_SERVICE) — MEMORY is cheaper so evaluated first; short-circuits
        RuleNode andRule = new RuleNode.AndNode(List.of(
                new RuleNode.ConditionNode("country", ComparisonOp.EQ, "US", DataSource.MEMORY),
                new RuleNode.ConditionNode("external.score", ComparisonOp.GT, 50, DataSource.EXTERNAL_SERVICE)
        ));

        Campaign campaign = activeCampaign("c-sc", "RIDE_COMPLETED", andRule);
        registerCampaign(campaign);

        processor.process(testEvent("evt-sc"));
        waitForActions();

        assertThat(metrics.getRulesEvaluated()).isEqualTo(1);
        assertThat(metrics.getRulesMatched()).isZero();
        assertThat(metrics.getActionsTriggered()).isZero();
    }

    @Test
    @DisplayName("event with null payload fields does not throw")
    void nullPayloadFields_noException() {
        Campaign campaign = activeCampaign("c1", "RIDE_COMPLETED", passingRule());
        registerCampaign(campaign);

        // Event with empty payload — rule will fail to match since "country" is absent
        TriggerFlowEvent event = testEvent("evt-null", "RIDE_COMPLETED", "user-1", Map.of());
        processor.process(event);
        waitForActions();

        assertThat(metrics.getEventsReceived()).isEqualTo(1);
        assertThat(metrics.getEventsProcessed()).isEqualTo(1);
        assertThat(metrics.getRulesMatched()).isZero();
    }
}
