package triggerflow.action;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential back-off retry policy with configurable jitter.
 *
 * <p>The delay for attempt {@code n} is computed as:
 * <pre>
 *   base  = initialDelay * backoffMultiplier^n
 *   jitter = base * jitterFactor * random(0..1)
 *   delay  = base + jitter
 * </pre>
 *
 * <p>Jitter prevents the "thundering herd" problem where many callers retry
 * simultaneously after a shared downstream failure.
 *
 * @param maxRetries        maximum number of retries before giving up
 * @param initialDelay      base delay for the first retry (attempt 0)
 * @param backoffMultiplier multiplicative factor applied per attempt
 * @param jitterFactor      proportion of the base delay added as random jitter
 */
public record RetryPolicy(int maxRetries, Duration initialDelay, double backoffMultiplier, double jitterFactor) {

    /** Sensible defaults: 3 retries, 100ms initial, 2x backoff, 10% jitter. */
    public static RetryPolicy defaults() {
        return new RetryPolicy(3, Duration.ofMillis(100), 2.0, 0.1);
    }

    /**
     * Computes the delay before the given retry attempt.
     *
     * @param attempt zero-based attempt number (0 = first retry)
     * @return the delay to wait before retrying
     */
    public Duration delayForAttempt(int attempt) {
        double base = initialDelay.toMillis() * Math.pow(backoffMultiplier, attempt);
        double jitter = base * jitterFactor * ThreadLocalRandom.current().nextDouble();
        return Duration.ofMillis((long) (base + jitter));
    }
}
