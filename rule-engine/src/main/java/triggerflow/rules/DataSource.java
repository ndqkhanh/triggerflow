package triggerflow.rules;

/**
 * Represents the origin of data used in a rule condition evaluation.
 *
 * <p>Each source carries a weight reflecting its relative cost: in-memory lookups
 * are cheap, database queries cost more, and external service calls are the most
 * expensive. The {@link RuleEvaluator} uses these weights to evaluate cheaper
 * conditions first, enabling early short-circuit and avoiding costly calls.
 */
public enum DataSource {

    MEMORY(1),
    DATABASE(10),
    EXTERNAL_SERVICE(100);

    private final int weight;

    DataSource(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }
}
