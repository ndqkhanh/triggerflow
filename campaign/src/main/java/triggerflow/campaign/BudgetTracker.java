package triggerflow.campaign;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe budget tracker for campaigns.
 *
 * <p>Tracks remaining budget per campaign using atomic operations.
 * High-limit campaigns (remaining &gt; 100,000) skip atomic tracking
 * for performance — they always return true without decrementing.</p>
 */
public class BudgetTracker {

    private static final long HIGH_LIMIT_THRESHOLD = 100_000L;

    private final ConcurrentHashMap<String, AtomicLong> budgets = new ConcurrentHashMap<>();

    /**
     * Initializes (or replaces) the budget for a campaign.
     */
    public void initialize(String campaignId, long maxBudget) {
        budgets.put(campaignId, new AtomicLong(maxBudget));
    }

    /**
     * Attempts to consume the given amount from the campaign's budget.
     *
     * <p>For campaigns with remaining &gt; 100,000, always returns true without
     * decrementing (skip tracking for high-limit campaigns).</p>
     *
     * @return true if the amount was consumed (or high-limit skip), false if insufficient budget
     */
    public boolean tryConsume(String campaignId, long amount) {
        AtomicLong remaining = budgets.get(campaignId);
        if (remaining == null) {
            return false;
        }

        // High-limit optimization: skip tracking
        if (remaining.get() > HIGH_LIMIT_THRESHOLD) {
            return true;
        }

        // Atomic compare-and-set loop
        while (true) {
            long current = remaining.get();
            if (current < amount) {
                return false;
            }
            if (remaining.compareAndSet(current, current - amount)) {
                return true;
            }
        }
    }

    /**
     * Returns the remaining budget for the given campaign, or 0 if unknown.
     */
    public long remaining(String campaignId) {
        AtomicLong remaining = budgets.get(campaignId);
        return remaining != null ? remaining.get() : 0L;
    }

    /**
     * Returns true if the campaign has remaining budget &gt; 0.
     */
    public boolean hasRemaining(String campaignId) {
        return remaining(campaignId) > 0;
    }
}
