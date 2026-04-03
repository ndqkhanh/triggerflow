package triggerflow.campaign;

import triggerflow.rules.RuleNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable campaign record. State changes produce new instances via {@link #withStatus(CampaignStatus)}.
 *
 * @param campaignId       unique campaign identifier
 * @param name             human-readable campaign name
 * @param status           current lifecycle status
 * @param triggerEventType the event type that triggers this campaign
 * @param conditions       rule tree for evaluating whether the event matches
 * @param actions          list of actions to execute on match
 * @param budget           budget constraint for the campaign
 * @param abTest           A/B test configuration
 * @param actionDelay      optional delay before executing actions
 * @param userLimit        per-user rate limit
 * @param startTime        campaign start time (nullable)
 * @param endTime          campaign end time (nullable)
 */
public record Campaign(
        String campaignId,
        String name,
        CampaignStatus status,
        String triggerEventType,
        RuleNode conditions,
        List<ActionDefinition> actions,
        BudgetConstraint budget,
        ABTestConfig abTest,
        Duration actionDelay,
        UserLimitation userLimit,
        Instant startTime,
        Instant endTime
) {

    public Campaign {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(triggerEventType, "triggerEventType");
        actions = actions != null ? List.copyOf(actions) : List.of();
        if (budget == null) budget = BudgetConstraint.unlimited();
        if (abTest == null) abTest = ABTestConfig.disabled();
        if (userLimit == null) userLimit = UserLimitation.unlimited();
    }

    /**
     * Returns a new Campaign with the given status, preserving all other fields.
     */
    public Campaign withStatus(CampaignStatus newStatus) {
        return new Campaign(
                campaignId, name, newStatus, triggerEventType, conditions,
                actions, budget, abTest, actionDelay, userLimit, startTime, endTime
        );
    }
}
