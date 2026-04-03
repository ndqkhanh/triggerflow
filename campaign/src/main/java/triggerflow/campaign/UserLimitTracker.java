package triggerflow.campaign;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe per-user action limit tracker.
 *
 * <p>Tracks action counts per (campaignId, userId) pair. The {@link #withinLimit} method
 * atomically checks and increments the count if within the configured limit.</p>
 */
public class UserLimitTracker {

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicInteger>> counters =
            new ConcurrentHashMap<>();

    /**
     * Checks if the user is within the action limit for the given campaign.
     * If within limit, atomically increments the count and returns true.
     * If at or above limit, returns false without incrementing.
     *
     * @param userId     the user identifier
     * @param campaignId the campaign identifier
     * @param limit      the user limitation to check against
     * @return true if within limit (count was incremented), false if at limit
     */
    public boolean withinLimit(String userId, String campaignId, UserLimitation limit) {
        ConcurrentHashMap<String, AtomicInteger> campaignCounters =
                counters.computeIfAbsent(campaignId, k -> new ConcurrentHashMap<>());
        AtomicInteger count = campaignCounters.computeIfAbsent(userId, k -> new AtomicInteger(0));

        while (true) {
            int current = count.get();
            if (current >= limit.maxActionsPerUser()) {
                return false;
            }
            if (count.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * Returns the current action count for the given user in the given campaign.
     *
     * @return the count, or 0 if no actions have been recorded
     */
    public int getCount(String userId, String campaignId) {
        ConcurrentHashMap<String, AtomicInteger> campaignCounters = counters.get(campaignId);
        if (campaignCounters == null) {
            return 0;
        }
        AtomicInteger count = campaignCounters.get(userId);
        return count != null ? count.get() : 0;
    }
}
