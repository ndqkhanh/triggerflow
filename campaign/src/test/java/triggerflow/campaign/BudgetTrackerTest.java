package triggerflow.campaign;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetTrackerTest {

    private BudgetTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new BudgetTracker();
    }

    @Test
    @DisplayName("Initialize and tryConsume within budget returns true")
    void tryConsume_withinBudget_returnsTrue() {
        tracker.initialize("c-1", 1000);

        assertThat(tracker.tryConsume("c-1", 500)).isTrue();
        assertThat(tracker.remaining("c-1")).isEqualTo(500);
    }

    @Test
    @DisplayName("tryConsume exceeding budget returns false")
    void tryConsume_exceedingBudget_returnsFalse() {
        tracker.initialize("c-1", 100);

        assertThat(tracker.tryConsume("c-1", 200)).isFalse();
        assertThat(tracker.remaining("c-1")).isEqualTo(100);
    }

    @Test
    @DisplayName("Remaining decreases after consume")
    void remaining_decreasesAfterConsume() {
        tracker.initialize("c-1", 1000);

        tracker.tryConsume("c-1", 300);
        assertThat(tracker.remaining("c-1")).isEqualTo(700);

        tracker.tryConsume("c-1", 200);
        assertThat(tracker.remaining("c-1")).isEqualTo(500);
    }

    @Test
    @DisplayName("hasRemaining true when budget left, false when depleted")
    void hasRemaining_reflectsBudgetState() {
        tracker.initialize("c-1", 100);

        assertThat(tracker.hasRemaining("c-1")).isTrue();

        tracker.tryConsume("c-1", 100);
        assertThat(tracker.hasRemaining("c-1")).isFalse();
    }

    @Test
    @DisplayName("High-limit campaign (>100K remaining) tryConsume always returns true")
    void highLimit_alwaysReturnsTrue() {
        tracker.initialize("c-1", 200_000);

        assertThat(tracker.tryConsume("c-1", 50)).isTrue();
        // Budget should NOT be decremented for high-limit campaigns
        assertThat(tracker.remaining("c-1")).isEqualTo(200_000);

        // Multiple consumes all return true without decrementing
        for (int i = 0; i < 100; i++) {
            assertThat(tracker.tryConsume("c-1", 10)).isTrue();
        }
        assertThat(tracker.remaining("c-1")).isEqualTo(200_000);
    }

    @Test
    @DisplayName("Thread safety: concurrent tryConsume total consumed <= budget")
    void concurrentTryConsume_totalWithinBudget() throws InterruptedException {
        long budget = 1000;
        tracker.initialize("c-1", budget);

        int threadCount = 10;
        int consumePerThread = 200;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    if (tracker.tryConsume("c-1", consumePerThread)) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Total consumed = successCount * consumePerThread <= budget
        long totalConsumed = (long) successCount.get() * consumePerThread;
        assertThat(totalConsumed).isLessThanOrEqualTo(budget);
        assertThat(tracker.remaining("c-1")).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Unknown campaign hasRemaining returns false")
    void unknownCampaign_hasRemaining_returnsFalse() {
        assertThat(tracker.hasRemaining("unknown")).isFalse();
        assertThat(tracker.remaining("unknown")).isZero();
    }

    @Test
    @DisplayName("Initialize replaces previous budget")
    void initialize_replacesPreviousBudget() {
        tracker.initialize("c-1", 1000);
        tracker.tryConsume("c-1", 500);
        assertThat(tracker.remaining("c-1")).isEqualTo(500);

        tracker.initialize("c-1", 2000);
        assertThat(tracker.remaining("c-1")).isEqualTo(2000);
    }
}
