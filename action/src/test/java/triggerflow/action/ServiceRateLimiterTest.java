package triggerflow.action;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ServiceRateLimiter}.
 */
class ServiceRateLimiterTest {

    private ServiceRateLimiter limiter(String service, int capacity, double refillRate) {
        return new ServiceRateLimiter(Map.of(
                service, new ServiceRateLimiter.RateConfig(capacity, refillRate)
        ));
    }

    @Test
    void firstRequest_allowed() {
        ServiceRateLimiter limiter = limiter("svc-a", 10, 1.0);

        assertThat(limiter.tryAcquire("svc-a")).isTrue();
    }

    @Test
    void burstUpToCapacity_allowed() {
        ServiceRateLimiter limiter = limiter("svc-a", 5, 1.0);

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("svc-a")).isTrue();
        }
    }

    @Test
    void exceedCapacity_rejected() {
        ServiceRateLimiter limiter = limiter("svc-a", 3, 1.0);

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("svc-a")).isTrue();
        }
        assertThat(limiter.tryAcquire("svc-a")).isFalse();
    }

    @Test
    void refillAfterTimePasses_allowedAgain() throws InterruptedException {
        // capacity=1, refill at 100/sec → 1 token per 10ms
        ServiceRateLimiter limiter = limiter("svc-a", 1, 100.0);

        assertThat(limiter.tryAcquire("svc-a")).isTrue();
        assertThat(limiter.tryAcquire("svc-a")).isFalse();

        // Wait for refill (10ms for 1 token at 100/sec)
        Thread.sleep(50);

        assertThat(limiter.tryAcquire("svc-a")).isTrue();
    }

    @Test
    void differentServices_independentLimits() {
        ServiceRateLimiter limiter = new ServiceRateLimiter(Map.of(
                "svc-a", new ServiceRateLimiter.RateConfig(2, 1.0),
                "svc-b", new ServiceRateLimiter.RateConfig(3, 1.0)
        ));

        // Exhaust svc-a
        assertThat(limiter.tryAcquire("svc-a")).isTrue();
        assertThat(limiter.tryAcquire("svc-a")).isTrue();
        assertThat(limiter.tryAcquire("svc-a")).isFalse();

        // svc-b still has tokens
        assertThat(limiter.tryAcquire("svc-b")).isTrue();
        assertThat(limiter.tryAcquire("svc-b")).isTrue();
        assertThat(limiter.tryAcquire("svc-b")).isTrue();
        assertThat(limiter.tryAcquire("svc-b")).isFalse();
    }

    @Test
    void unknownService_getsDefaultRate() {
        ServiceRateLimiter limiter = new ServiceRateLimiter(Map.of());

        // Default is capacity=100, so first request should be allowed
        assertThat(limiter.tryAcquire("unknown-service")).isTrue();
    }

    @Test
    void unknownService_defaultCapacityIs100() {
        ServiceRateLimiter limiter = new ServiceRateLimiter(Map.of());

        // Exhaust 100 tokens (default capacity)
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire("unknown")).isTrue();
        }
        assertThat(limiter.tryAcquire("unknown")).isFalse();
    }

    @Test
    void availableTokens_fullCapacityForNewService() {
        ServiceRateLimiter limiter = limiter("svc-a", 10, 1.0);

        assertThat(limiter.availableTokens("svc-a")).isEqualTo(10.0);
    }

    @Test
    void availableTokens_decreasesAfterAcquire() {
        ServiceRateLimiter limiter = limiter("svc-a", 10, 1.0);

        limiter.tryAcquire("svc-a");
        limiter.tryAcquire("svc-a");

        assertThat(limiter.availableTokens("svc-a")).isLessThanOrEqualTo(8.1);
        assertThat(limiter.availableTokens("svc-a")).isGreaterThanOrEqualTo(7.9);
    }

    @Test
    void rateConfig_rejectsInvalidCapacity() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ServiceRateLimiter.RateConfig(0, 1.0));
    }

    @Test
    void rateConfig_rejectsInvalidRefillRate() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ServiceRateLimiter.RateConfig(10, -1.0));
    }

    @Test
    void concurrentTryAcquire_threadSafe() throws InterruptedException {
        ServiceRateLimiter limiter = limiter("svc-a", 100, 0.001);
        int threadCount = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger allowed = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 10; j++) {
                        if (limiter.tryAcquire("svc-a")) {
                            allowed.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();

        // With capacity 100 and 200 total attempts, exactly 100 should succeed
        assertThat(allowed.get()).isEqualTo(100);
    }
}
