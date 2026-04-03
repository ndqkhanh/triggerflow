package triggerflow.campaign;

/**
 * Lifecycle states for a campaign.
 *
 * <h3>State Diagram</h3>
 * <pre>
 *   DRAFT ──▶ ACTIVE ──▶ PAUSED ──▶ ACTIVE
 *     │          │          │
 *     │          ▼          │
 *     │      COMPLETED      │
 *     │                     │
 *     ▼─────── CANCELLED ◀──┘
 * </pre>
 */
public enum CampaignStatus {
    DRAFT,
    ACTIVE,
    PAUSED,
    COMPLETED,
    CANCELLED
}
