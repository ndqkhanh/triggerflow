package triggerflow.dedup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import triggerflow.common.EventKey;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BloomPrefilterTest {

    @Test
    @DisplayName("Never-added key returns false (definite negative)")
    void mightContain_neverAdded_returnsFalse() {
        var bloom = BloomPrefilter.create(1000, 0.01);
        var key = new EventKey("evt-999", "RIDE_COMPLETED");

        assertThat(bloom.mightContain(key)).isFalse();
    }

    @Test
    @DisplayName("Added key returns true")
    void mightContain_addedKey_returnsTrue() {
        var bloom = BloomPrefilter.create(1000, 0.01);
        var key = new EventKey("evt-001", "PAYMENT_SUCCEEDED");

        bloom.add(key);

        assertThat(bloom.mightContain(key)).isTrue();
    }

    @Test
    @DisplayName("Empty filter returns false for all queries")
    void emptyFilter_allReturnFalse() {
        var bloom = BloomPrefilter.create(1000, 0.01);

        for (int i = 0; i < 100; i++) {
            assertThat(bloom.mightContain(new EventKey("evt-" + i, "TYPE"))).isFalse();
        }
    }

    @Test
    @DisplayName("Different eventType with same eventId are distinct")
    void differentEventType_sameId_distinct() {
        var bloom = BloomPrefilter.create(1000, 0.01);
        var key1 = new EventKey("evt-001", "RIDE_COMPLETED");
        var key2 = new EventKey("evt-001", "PAYMENT_SUCCEEDED");

        bloom.add(key1);

        assertThat(bloom.mightContain(key1)).isTrue();
        // key2 was never added — should be false (no false positive for 1 element)
        assertThat(bloom.mightContain(key2)).isFalse();
    }

    @Test
    @DisplayName("False positive rate stays below configured rate after 10K insertions")
    void falsePositiveRate_belowConfigured() {
        double targetFpr = 0.01;
        int insertions = 10_000;
        var bloom = BloomPrefilter.create(insertions, targetFpr);

        // Insert 10K unique keys
        for (int i = 0; i < insertions; i++) {
            bloom.add(new EventKey("evt-" + i, "TYPE"));
        }

        // Test 10K keys that were NEVER inserted
        int falsePositives = 0;
        int testSize = 10_000;
        for (int i = insertions; i < insertions + testSize; i++) {
            if (bloom.mightContain(new EventKey("evt-" + i, "TYPE"))) {
                falsePositives++;
            }
        }

        double observedFpr = (double) falsePositives / testSize;
        // Allow 3x the target FPR as margin for statistical variance
        assertThat(observedFpr).isLessThan(targetFpr * 3);
    }

    @Test
    @DisplayName("Expected false positive rate is zero for empty filter")
    void expectedFpr_emptyFilter_isZero() {
        var bloom = BloomPrefilter.create(1000, 0.01);
        assertThat(bloom.expectedFalsePositiveRate()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Expected false positive rate increases with insertions")
    void expectedFpr_increasesWithInsertions() {
        var bloom = BloomPrefilter.create(1000, 0.01);

        bloom.add(new EventKey("evt-1", "TYPE"));
        double fpr1 = bloom.expectedFalsePositiveRate();

        for (int i = 2; i <= 100; i++) {
            bloom.add(new EventKey("evt-" + i, "TYPE"));
        }
        double fpr100 = bloom.expectedFalsePositiveRate();

        assertThat(fpr100).isGreaterThan(fpr1);
    }

    @Test
    @DisplayName("Size tracks number of additions")
    void size_tracksAdditions() {
        var bloom = BloomPrefilter.create(1000, 0.01);

        assertThat(bloom.size()).isZero();
        bloom.add(new EventKey("evt-1", "TYPE"));
        assertThat(bloom.size()).isEqualTo(1);
        bloom.add(new EventKey("evt-2", "TYPE"));
        assertThat(bloom.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Size includes duplicate additions")
    void size_includesDuplicateAdds() {
        var bloom = BloomPrefilter.create(1000, 0.01);
        var key = new EventKey("evt-1", "TYPE");

        bloom.add(key);
        bloom.add(key);

        assertThat(bloom.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Optimal bit count for known parameters")
    void optimalBitCount_knownParameters() {
        // For n=1000, p=0.01: m = -1000 * ln(0.01) / (ln2)^2 = ~9585
        int m = BloomPrefilter.optimalBitCount(1000, 0.01);
        assertThat(m).isBetween(9500, 9700);
    }

    @Test
    @DisplayName("Optimal hash count for known parameters")
    void optimalHashCount_knownParameters() {
        // For m=9585, n=1000: k = (9585/1000) * ln2 = ~6.64 -> rounds to 7
        int k = BloomPrefilter.optimalHashCount(9585, 1000);
        assertThat(k).isEqualTo(7);
    }

    @Test
    @DisplayName("Thread safety: concurrent adds do not corrupt the filter")
    void threadSafety_concurrentAdds_noCorruption() throws Exception {
        int threads = 8;
        int addsPerThread = 1000;
        var bloom = BloomPrefilter.create(threads * addsPerThread, 0.01);
        var latch = new CountDownLatch(threads);

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    for (int i = 0; i < addsPerThread; i++) {
                        bloom.add(new EventKey("t" + threadId + "-evt-" + i, "TYPE"));
                    }
                    latch.countDown();
                });
            }
            latch.await();
        }

        // All keys should be present
        assertThat(bloom.size()).isEqualTo(threads * addsPerThread);
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < addsPerThread; i++) {
                assertThat(bloom.mightContain(new EventKey("t" + t + "-evt-" + i, "TYPE")))
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("Thread safety: concurrent mightContain during adds")
    void threadSafety_concurrentReadsAndWrites() throws Exception {
        var bloom = BloomPrefilter.create(10_000, 0.01);
        var errors = new AtomicInteger(0);
        var latch = new CountDownLatch(2);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            // Writer thread
            executor.submit(() -> {
                for (int i = 0; i < 5000; i++) {
                    bloom.add(new EventKey("evt-" + i, "TYPE"));
                }
                latch.countDown();
            });

            // Reader thread
            executor.submit(() -> {
                try {
                    for (int i = 0; i < 5000; i++) {
                        // Should not throw any exceptions
                        bloom.mightContain(new EventKey("evt-" + i, "TYPE"));
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
                latch.countDown();
            });

            latch.await();
        }

        assertThat(errors.get()).isZero();
    }

    @Test
    @DisplayName("Invalid parameters throw IllegalArgumentException")
    void invalidParameters_throw() {
        assertThatThrownBy(() -> BloomPrefilter.create(0, 0.01))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BloomPrefilter.create(1000, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BloomPrefilter.create(1000, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
