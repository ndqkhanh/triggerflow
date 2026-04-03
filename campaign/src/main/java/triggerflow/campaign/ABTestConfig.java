package triggerflow.campaign;

import java.util.Map;

/**
 * A/B test configuration mapping variant names to traffic proportions.
 * Proportions should sum to 1.0.
 *
 * @param variants map of variant name to its traffic proportion; defensively copied
 */
public record ABTestConfig(Map<String, Double> variants) {

    public ABTestConfig {
        variants = Map.copyOf(variants);
    }

    /**
     * Returns a disabled A/B test config with a single "default" variant at 100%.
     */
    public static ABTestConfig disabled() {
        return new ABTestConfig(Map.of("default", 1.0));
    }
}
