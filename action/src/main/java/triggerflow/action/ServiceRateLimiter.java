package triggerflow.action;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-service token bucket rate limiter.
 *
 * <p>Each downstream service gets its own {@link TokenBucket} with configurable
 * capacity and refill rate. The limiter uses lazy refill: tokens are calculated
 * on each request based on elapsed time, avoiding background threads and
 * scaling to many services.
 *
 * <p>This follows the exact pattern from GrabFlow's
 * {@code grabflow.gateway.ratelimit.TokenBucket} — a lazy-refill token bucket
 * that is O(1) per acquire and requires no timer threads.
 *
 * @see RateConfig
 */
public class ServiceRateLimiter {

    /**
     * Configuration for a single service's rate limit.
     *
     * @param capacity              max tokens (burst size)
     * @param refillRatePerSecond   tokens added per second (sustained rate)
     */
    public record RateConfig(int capacity, double refillRatePerSecond) {

        public RateConfig {
            if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
            if (refillRatePerSecond <= 0) throw new IllegalArgumentException("Refill rate must be positive");
        }
    }

    // -------------------------------------------------------------------------
    // Inner class — token bucket per service (mirrors GrabFlow's Bucket)
    // -------------------------------------------------------------------------

    /**
     * A single token bucket for one service. Thread-safe via {@code synchronized}.
     *
     * <p>Uses lazy refill: tokens are computed on access, not on a timer.
     * This avoids background threads and scales to millions of buckets.
     *
     * <h3>Algorithm</h3>
     * <pre>
     *   tokens = min(capacity, tokens + (now - lastRefill) * refillRate)
     *   if tokens &ge; 1:
     *       tokens -= 1
     *       ALLOW
     *   else:
     *       REJECT
     * </pre>
     */
    static final class TokenBucket {

        private final int capacity;
        private final double refillRatePerSecond;
        private double tokens;
        private long lastRefillNanos;

        TokenBucket(int capacity, double refillRatePerSecond) {
            this.capacity = capacity;
            this.refillRatePerSecond = refillRatePerSecond;
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryAcquire() {
            refill(System.nanoTime());
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        synchronized double availableTokens() {
            refill(System.nanoTime());
            return tokens;
        }

        private void refill(long nowNanos) {
            long elapsedNanos = nowNanos - lastRefillNanos;
            if (elapsedNanos <= 0) return;

            double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
            double newTokens = elapsedSeconds * refillRatePerSecond;
            tokens = Math.min(capacity, tokens + newTokens);
            lastRefillNanos = nowNanos;
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final Map<String, RateConfig> serviceRates;
    private final RateConfig defaultRate;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Creates a rate limiter with per-service configurations.
     *
     * <p>Services not in the map receive a default rate of 100 capacity / 10 per second.
     *
     * @param serviceRates per-service rate configurations
     */
    public ServiceRateLimiter(Map<String, RateConfig> serviceRates) {
        this.serviceRates = Map.copyOf(serviceRates);
        this.defaultRate = new RateConfig(100, 10.0);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Attempts to acquire a single token for the given service.
     *
     * @param serviceName the downstream service name
     * @return true if the request is allowed, false if rate-limited
     */
    public boolean tryAcquire(String serviceName) {
        TokenBucket bucket = buckets.computeIfAbsent(serviceName, this::createBucket);
        return bucket.tryAcquire();
    }

    /**
     * Returns the number of tokens currently available for a service.
     * Returns the full capacity if the service has not been seen.
     */
    public double availableTokens(String serviceName) {
        TokenBucket bucket = buckets.get(serviceName);
        if (bucket == null) {
            RateConfig config = serviceRates.getOrDefault(serviceName, defaultRate);
            return config.capacity();
        }
        return bucket.availableTokens();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private TokenBucket createBucket(String serviceName) {
        RateConfig config = serviceRates.getOrDefault(serviceName, defaultRate);
        return new TokenBucket(config.capacity(), config.refillRatePerSecond());
    }
}
