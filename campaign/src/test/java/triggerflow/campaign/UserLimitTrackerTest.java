package triggerflow.campaign;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class UserLimitTrackerTest {

    private UserLimitTracker tracker;
    private UserLimitation limit;

    @BeforeEach
    void setUp() {
        tracker = new UserLimitTracker();
        limit = new UserLimitation(3, Duration.ofHours(1));
    }

    @Test
    @DisplayName("Within limit returns true and increments count")
    void withinLimit_returnsTrue_incrementsCount() {
        assertThat(tracker.withinLimit("user-1", "c-1", limit)).isTrue();
        assertThat(tracker.getCount("user-1", "c-1")).isEqualTo(1);

        assertThat(tracker.withinLimit("user-1", "c-1", limit)).isTrue();
        assertThat(tracker.getCount("user-1", "c-1")).isEqualTo(2);
    }

    @Test
    @DisplayName("At limit returns false")
    void atLimit_returnsFalse() {
        // Consume all 3 allowed actions
        assertThat(tracker.withinLimit("user-1", "c-1", limit)).isTrue();
        assertThat(tracker.withinLimit("user-1", "c-1", limit)).isTrue();
        assertThat(tracker.withinLimit("user-1", "c-1", limit)).isTrue();

        // 4th attempt should fail
        assertThat(tracker.withinLimit("user-1", "c-1", limit)).isFalse();
        assertThat(tracker.getCount("user-1", "c-1")).isEqualTo(3);
    }

    @Test
    @DisplayName("Different campaigns have independent limits")
    void differentCampaigns_independentLimits() {
        // Fill limit for c-1
        tracker.withinLimit("user-1", "c-1", limit);
        tracker.withinLimit("user-1", "c-1", limit);
        tracker.withinLimit("user-1", "c-1", limit);
        assertThat(tracker.withinLimit("user-1", "c-1", limit)).isFalse();

        // c-2 should still have capacity
        assertThat(tracker.withinLimit("user-1", "c-2", limit)).isTrue();
        assertThat(tracker.getCount("user-1", "c-2")).isEqualTo(1);
    }

    @Test
    @DisplayName("Different users have independent limits")
    void differentUsers_independentLimits() {
        // Fill limit for user-1
        tracker.withinLimit("user-1", "c-1", limit);
        tracker.withinLimit("user-1", "c-1", limit);
        tracker.withinLimit("user-1", "c-1", limit);
        assertThat(tracker.withinLimit("user-1", "c-1", limit)).isFalse();

        // user-2 should still have capacity
        assertThat(tracker.withinLimit("user-2", "c-1", limit)).isTrue();
        assertThat(tracker.getCount("user-2", "c-1")).isEqualTo(1);
    }

    @Test
    @DisplayName("getCount returns 0 for new user")
    void getCount_newUser_returnsZero() {
        assertThat(tracker.getCount("new-user", "c-1")).isZero();
    }

    @Test
    @DisplayName("Thread safety: concurrent withinLimit calls respect limit")
    void concurrentWithinLimit_respectsLimit() throws InterruptedException {
        var strictLimit = new UserLimitation(5, Duration.ofHours(1));
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    if (tracker.withinLimit("user-1", "c-1", strictLimit)) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Exactly 5 should succeed (the limit)
        assertThat(successCount.get()).isEqualTo(5);
        assertThat(tracker.getCount("user-1", "c-1")).isEqualTo(5);
    }
}
