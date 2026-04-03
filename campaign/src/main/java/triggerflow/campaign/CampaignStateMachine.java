package triggerflow.campaign;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import triggerflow.rules.RuleNode;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Campaign lifecycle state machine with validated transitions.
 *
 * <h3>State Diagram</h3>
 * <pre>
 *   DRAFT ──▶ ACTIVE ──▶ PAUSED
 *     │          │  ▲        │
 *     │          │  └────────┘
 *     │          ▼
 *     │      COMPLETED
 *     │
 *     ▼─────── CANCELLED ◀── ACTIVE, PAUSED
 * </pre>
 *
 * <p>Each transition is validated: only legal state transitions are allowed.
 * Concurrent transitions on the same campaign are serialized via synchronized.</p>
 */
public class CampaignStateMachine {

    private static final Logger log = LoggerFactory.getLogger(CampaignStateMachine.class);

    /** Legal transitions: from-state -> set of allowed to-states */
    private static final Map<CampaignStatus, Set<CampaignStatus>> TRANSITIONS = Map.of(
            CampaignStatus.DRAFT, EnumSet.of(CampaignStatus.ACTIVE, CampaignStatus.CANCELLED),
            CampaignStatus.ACTIVE, EnumSet.of(CampaignStatus.PAUSED, CampaignStatus.COMPLETED, CampaignStatus.CANCELLED),
            CampaignStatus.PAUSED, EnumSet.of(CampaignStatus.ACTIVE, CampaignStatus.CANCELLED),
            CampaignStatus.COMPLETED, EnumSet.noneOf(CampaignStatus.class),
            CampaignStatus.CANCELLED, EnumSet.noneOf(CampaignStatus.class)
    );

    private final ConcurrentHashMap<String, Campaign> campaigns = new ConcurrentHashMap<>();

    /**
     * Creates a new campaign in DRAFT state.
     *
     * @throws IllegalStateException if a campaign with this ID already exists
     */
    public Campaign createCampaign(String campaignId, String name, String triggerEventType,
                                   RuleNode conditions, List<ActionDefinition> actions,
                                   BudgetConstraint budget, ABTestConfig abTest,
                                   Duration actionDelay, UserLimitation userLimit,
                                   Instant startTime, Instant endTime) {
        var campaign = new Campaign(
                campaignId, name, CampaignStatus.DRAFT, triggerEventType, conditions,
                actions, budget, abTest, actionDelay, userLimit, startTime, endTime
        );
        if (campaigns.putIfAbsent(campaignId, campaign) != null) {
            throw new IllegalStateException("Campaign already exists: " + campaignId);
        }
        log.info("Campaign created: {} (name={})", campaignId, name);
        return campaign;
    }

    /**
     * Transitions a campaign to a new state, validating the transition is legal.
     *
     * @throws IllegalStateException    if the transition is not allowed
     * @throws IllegalArgumentException if the campaign does not exist
     */
    public synchronized Campaign transition(String campaignId, CampaignStatus newStatus) {
        Campaign current = campaigns.get(campaignId);
        if (current == null) {
            throw new IllegalArgumentException("Campaign not found: " + campaignId);
        }

        Set<CampaignStatus> allowed = TRANSITIONS.get(current.status());
        if (!allowed.contains(newStatus)) {
            throw new IllegalStateException(String.format(
                    "Cannot transition campaign %s from %s to %s (allowed: %s)",
                    campaignId, current.status(), newStatus, allowed));
        }

        Campaign updated = current.withStatus(newStatus);
        campaigns.put(campaignId, updated);
        log.info("Campaign {} transitioned: {} -> {}", campaignId, current.status(), newStatus);
        return updated;
    }

    /**
     * Retrieves a campaign by ID.
     *
     * @throws IllegalArgumentException if the campaign does not exist
     */
    public Campaign getCampaign(String campaignId) {
        Campaign campaign = campaigns.get(campaignId);
        if (campaign == null) {
            throw new IllegalArgumentException("Campaign not found: " + campaignId);
        }
        return campaign;
    }

    /**
     * Returns the count of campaigns currently in ACTIVE status.
     */
    public int activeCampaignCount() {
        return (int) campaigns.values().stream()
                .filter(c -> c.status() == CampaignStatus.ACTIVE)
                .count();
    }
}
