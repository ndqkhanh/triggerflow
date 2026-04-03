package triggerflow.action;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RetryPolicy}.
 */
class RetryPolicyTest {

    @Test
    void defaults_haveSaneValues() {
        RetryPolicy policy = RetryPolicy.defaults();

        assertThat(policy.maxRetries()).isEqualTo(3);
        assertThat(policy.initialDelay()).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.backoffMultiplier()).isEqualTo(2.0);
        assertThat(policy.jitterFactor()).isEqualTo(0.1);
    }

    @Test
    void attempt0_returnsApproximatelyInitialDelay() {
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(100), 2.0, 0.0);

        Duration delay = policy.delayForAttempt(0);

        // With zero jitter, attempt 0 = initialDelay * 2^0 = 100ms
        assertThat(delay.toMillis()).isEqualTo(100);
    }

    @Test
    void delayIncreasesWithAttemptNumber() {
        RetryPolicy policy = new RetryPolicy(5, Duration.ofMillis(100), 2.0, 0.0);

        Duration delay0 = policy.delayForAttempt(0);
        Duration delay1 = policy.delayForAttempt(1);
        Duration delay2 = policy.delayForAttempt(2);

        // With zero jitter: 100, 200, 400
        assertThat(delay0.toMillis()).isEqualTo(100);
        assertThat(delay1.toMillis()).isEqualTo(200);
        assertThat(delay2.toMillis()).isEqualTo(400);
    }

    @Test
    void jitterAddsRandomness() {
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(100), 2.0, 0.5);

        // Run multiple times — with 50% jitter, delay should be in [100, 150]
        for (int i = 0; i < 20; i++) {
            Duration delay = policy.delayForAttempt(0);
            assertThat(delay.toMillis()).isBetween(100L, 150L);
        }
    }

    @Test
    void backoffMultiplier_appliedExponentially() {
        RetryPolicy policy = new RetryPolicy(5, Duration.ofMillis(50), 3.0, 0.0);

        // 50 * 3^0 = 50, 50 * 3^1 = 150, 50 * 3^2 = 450
        assertThat(policy.delayForAttempt(0).toMillis()).isEqualTo(50);
        assertThat(policy.delayForAttempt(1).toMillis()).isEqualTo(150);
        assertThat(policy.delayForAttempt(2).toMillis()).isEqualTo(450);
    }

    @Test
    void zeroJitter_producesConsistentDelays() {
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(200), 2.0, 0.0);

        Duration first = policy.delayForAttempt(1);
        Duration second = policy.delayForAttempt(1);

        assertThat(first).isEqualTo(second);
    }
}
