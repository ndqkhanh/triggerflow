package triggerflow.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TriggerFlowConfigTest {

    @Test
    @DisplayName("Defaults return sane production values")
    void defaults_returnsSaneValues() {
        var config = TriggerFlowConfig.defaults();

        assertThat(config.consumerParallelism()).isEqualTo(4);
        assertThat(config.dedupWindow()).isEqualTo(Duration.ofHours(24));
        assertThat(config.cacheMaxSize()).isEqualTo(1_000_000);
        assertThat(config.bloomFalsePositiveRate()).isEqualTo(0.001);
        assertThat(config.bloomExpectedInsertions()).isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("Custom values are preserved")
    void customValues_preserved() {
        var config = new TriggerFlowConfig(
                8, Duration.ofHours(12), 500_000, 0.01, 2_000_000);

        assertThat(config.consumerParallelism()).isEqualTo(8);
        assertThat(config.dedupWindow()).isEqualTo(Duration.ofHours(12));
        assertThat(config.cacheMaxSize()).isEqualTo(500_000);
        assertThat(config.bloomFalsePositiveRate()).isEqualTo(0.01);
        assertThat(config.bloomExpectedInsertions()).isEqualTo(2_000_000);
    }

    @Test
    @DisplayName("Defaults parallelism is at least 1")
    void defaults_parallelismAtLeastOne() {
        assertThat(TriggerFlowConfig.defaults().consumerParallelism()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Defaults dedup window is positive")
    void defaults_dedupWindowPositive() {
        assertThat(TriggerFlowConfig.defaults().dedupWindow()).isPositive();
    }
}
