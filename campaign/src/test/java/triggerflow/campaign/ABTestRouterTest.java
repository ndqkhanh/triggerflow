package triggerflow.campaign;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ABTestRouterTest {

    private ABTestRouter router;

    @BeforeEach
    void setUp() {
        router = new ABTestRouter();
    }

    @Test
    @DisplayName("Same userId always returns same variant (deterministic)")
    void sameUserId_deterministic() {
        var config = new ABTestConfig(Map.of("A", 0.5, "B", 0.5));

        String firstResult = router.getVariant("user-42", config);

        for (int i = 0; i < 100; i++) {
            assertThat(router.getVariant("user-42", config)).isEqualTo(firstResult);
        }
    }

    @Test
    @DisplayName("Different userIds are distributed across variants")
    void differentUserIds_distributedAcrossVariants() {
        var config = new ABTestConfig(Map.of("A", 0.5, "B", 0.5));

        Map<String, Integer> distribution = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            String variant = router.getVariant("user-" + i, config);
            distribution.merge(variant, 1, Integer::sum);
        }

        // Both variants should be represented
        assertThat(distribution).containsKeys("A", "B");
        assertThat(distribution.get("A")).isGreaterThan(0);
        assertThat(distribution.get("B")).isGreaterThan(0);
    }

    @Test
    @DisplayName("Single variant (disabled) always returns 'default'")
    void singleVariant_alwaysReturnsDefault() {
        var config = ABTestConfig.disabled();

        for (int i = 0; i < 100; i++) {
            assertThat(router.getVariant("user-" + i, config)).isEqualTo("default");
        }
    }

    @Test
    @DisplayName("Two variants 50/50 roughly even distribution (tolerance +-10%)")
    void twoVariants_evenDistribution() {
        var config = new ABTestConfig(Map.of("A", 0.5, "B", 0.5));

        Map<String, Integer> distribution = new HashMap<>();
        int total = 1000;
        for (int i = 0; i < total; i++) {
            String variant = router.getVariant("user-" + i, config);
            distribution.merge(variant, 1, Integer::sum);
        }

        int countA = distribution.getOrDefault("A", 0);
        int countB = distribution.getOrDefault("B", 0);

        // Each should be roughly 500 with +-10% tolerance (400-600)
        assertThat(countA).isBetween(400, 600);
        assertThat(countB).isBetween(400, 600);
    }

    @Test
    @DisplayName("Three variants 60/20/20 proportional distribution")
    void threeVariants_proportionalDistribution() {
        var config = new ABTestConfig(Map.of("A", 0.6, "B", 0.2, "C", 0.2));

        Map<String, Integer> distribution = new HashMap<>();
        int total = 1000;
        for (int i = 0; i < total; i++) {
            String variant = router.getVariant("user-" + i, config);
            distribution.merge(variant, 1, Integer::sum);
        }

        int countA = distribution.getOrDefault("A", 0);
        int countB = distribution.getOrDefault("B", 0);
        int countC = distribution.getOrDefault("C", 0);

        // A ~ 600 (+-10%), B ~ 200 (+-10%), C ~ 200 (+-10%)
        assertThat(countA).isBetween(500, 700);
        assertThat(countB).isBetween(100, 300);
        assertThat(countC).isBetween(100, 300);
    }

    @Test
    @DisplayName("Empty config throws IllegalArgumentException")
    void emptyConfig_throws() {
        var config = new ABTestConfig(Map.of());

        assertThatThrownBy(() -> router.getVariant("user-1", config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no variants");
    }
}
