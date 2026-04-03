package triggerflow.campaign;

import java.time.Duration;

/**
 * Per-user action rate limit for a campaign.
 *
 * @param maxActionsPerUser maximum number of actions a single user can receive
 * @param timeWindow        the time window for the rate limit
 */
public record UserLimitation(int maxActionsPerUser, Duration timeWindow) {

    /**
     * Returns an effectively unlimited user limitation.
     */
    public static UserLimitation unlimited() {
        return new UserLimitation(Integer.MAX_VALUE, Duration.ofDays(365));
    }
}
