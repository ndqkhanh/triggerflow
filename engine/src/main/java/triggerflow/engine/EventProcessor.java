package triggerflow.engine;

import triggerflow.action.ActionDispatcher;
import triggerflow.action.ActionRequest;
import triggerflow.action.ActionType;
import triggerflow.campaign.ABTestRouter;
import triggerflow.campaign.ActionDefinition;
import triggerflow.campaign.BudgetTracker;
import triggerflow.campaign.Campaign;
import triggerflow.campaign.CampaignStatus;
import triggerflow.campaign.EventPrefilter;
import triggerflow.campaign.UserLimitTracker;
import triggerflow.common.EventKey;
import triggerflow.common.TriggerFlowEvent;
import triggerflow.dedup.DeduplicationFilter;
import triggerflow.dedup.DeduplicationResult;
import triggerflow.rules.EvaluationResult;
import triggerflow.rules.EventContext;
import triggerflow.rules.RuleEvaluator;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Core single-event processing pipeline — the heart of TriggerFlow.
 *
 * <p>Processes one event through the full pipeline:
 * <ol>
 *   <li>Assign {@link EventKey}</li>
 *   <li>Deduplication check (three-tier: bloom, cache, persistent)</li>
 *   <li>Event pre-filtering to find matching campaigns</li>
 *   <li>For each matching campaign: status check, time window, budget,
 *       user limit, A/B test routing, rule evaluation, action dispatch</li>
 *   <li>Record event in dedup store</li>
 * </ol>
 *
 * <p>All dependencies are injected via the constructor, making this class
 * fully testable with in-memory implementations.
 */
public class EventProcessor {

    private final DeduplicationFilter dedupFilter;
    private final EventPrefilter eventPrefilter;
    private final RuleEvaluator ruleEvaluator;
    private final BudgetTracker budgetTracker;
    private final ABTestRouter abTestRouter;
    private final UserLimitTracker userLimitTracker;
    private final ActionDispatcher actionDispatcher;
    private final ProcessingMetrics metrics;

    /**
     * @param dedupFilter      three-tier deduplication filter
     * @param eventPrefilter   O(1) campaign lookup by event type
     * @param ruleEvaluator    rule tree evaluator with weighted short-circuit
     * @param budgetTracker    per-campaign budget tracker
     * @param abTestRouter     deterministic A/B test variant router
     * @param userLimitTracker per-user action limit tracker
     * @param actionDispatcher downstream action dispatcher with retry
     * @param metrics          processing metrics accumulator
     */
    public EventProcessor(
            DeduplicationFilter dedupFilter,
            EventPrefilter eventPrefilter,
            RuleEvaluator ruleEvaluator,
            BudgetTracker budgetTracker,
            ABTestRouter abTestRouter,
            UserLimitTracker userLimitTracker,
            ActionDispatcher actionDispatcher,
            ProcessingMetrics metrics
    ) {
        this.dedupFilter = Objects.requireNonNull(dedupFilter, "dedupFilter");
        this.eventPrefilter = Objects.requireNonNull(eventPrefilter, "eventPrefilter");
        this.ruleEvaluator = Objects.requireNonNull(ruleEvaluator, "ruleEvaluator");
        this.budgetTracker = Objects.requireNonNull(budgetTracker, "budgetTracker");
        this.abTestRouter = Objects.requireNonNull(abTestRouter, "abTestRouter");
        this.userLimitTracker = Objects.requireNonNull(userLimitTracker, "userLimitTracker");
        this.actionDispatcher = Objects.requireNonNull(actionDispatcher, "actionDispatcher");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Processes a single event through the full TriggerFlow pipeline.
     *
     * @param event the event to process
     */
    public void process(TriggerFlowEvent event) {
        metrics.incrementEventsReceived();

        // Step 1: Assign EventKey
        EventKey key = new EventKey(event.eventId(), event.eventType());

        // Step 2: Deduplication check
        DeduplicationResult dedupResult = dedupFilter.check(key);
        if (dedupResult != DeduplicationResult.NEW_EVENT) {
            metrics.incrementDuplicatesFiltered();
            return;
        }

        // Step 3: Event prefiltering — get matching campaigns
        List<Campaign> campaigns = eventPrefilter.getCampaigns(event.eventType());
        if (campaigns.isEmpty()) {
            // Record in dedup even if no campaigns (prevent re-processing)
            dedupFilter.recordProcessed(key, false);
            metrics.incrementEventsProcessed();
            return;
        }

        // Step 4: Evaluate each matching campaign
        boolean anyActionTriggered = false;
        EventContext ctx = new EventContext(event, Map.of());

        for (Campaign campaign : campaigns) {
            // 4a: Status check
            if (campaign.status() != CampaignStatus.ACTIVE) {
                continue;
            }

            // 4b: Time window check
            Instant now = Instant.now();
            if (campaign.startTime() != null && now.isBefore(campaign.startTime())) {
                continue;
            }
            if (campaign.endTime() != null && now.isAfter(campaign.endTime())) {
                continue;
            }

            // 4c: Budget check
            if (!budgetTracker.hasRemaining(campaign.campaignId())) {
                continue;
            }

            // 4d: User limit check
            if (!userLimitTracker.withinLimit(event.userId(), campaign.campaignId(), campaign.userLimit())) {
                continue;
            }

            // 4e: A/B test routing
            String variant = abTestRouter.getVariant(event.userId(), campaign.abTest());

            // 4f: Rule evaluation (weighted short-circuit)
            metrics.incrementRulesEvaluated();
            EvaluationResult evalResult = ruleEvaluator.evaluate(campaign.conditions(), ctx);

            if (evalResult.matched()) {
                metrics.incrementRulesMatched();

                // 4g: Consume budget
                budgetTracker.tryConsume(campaign.campaignId(), 1);

                // 4h: Dispatch actions
                for (ActionDefinition actionDef : campaign.actions()) {
                    ActionRequest request = new ActionRequest(
                            null, // auto-generate ID
                            ActionType.valueOf(actionDef.actionType()),
                            actionDef.targetService(),
                            actionDef.params(),
                            campaign.actionDelay(),
                            campaign.campaignId(),
                            event.userId(),
                            null // auto-timestamp
                    );
                    try {
                        actionDispatcher.dispatch(request);
                        metrics.incrementActionsTriggered();
                        anyActionTriggered = true;
                    } catch (Exception e) {
                        metrics.incrementActionsFailed();
                    }
                }
            }
        }

        // Step 5: Record in dedup
        dedupFilter.recordProcessed(key, anyActionTriggered);
        metrics.incrementEventsProcessed();
    }
}
