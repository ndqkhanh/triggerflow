package triggerflow.campaign;

/**
 * Budget constraint for a campaign, defining the maximum spend.
 *
 * @param maxAmount maximum budget amount in the smallest currency unit
 * @param currency  ISO 4217 currency code
 */
public record BudgetConstraint(long maxAmount, String currency) {

    /**
     * Returns an effectively unlimited budget constraint.
     */
    public static BudgetConstraint unlimited() {
        return new BudgetConstraint(Long.MAX_VALUE, "USD");
    }
}
