package triggerflow.dedup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import triggerflow.common.EventKey;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryPersistentDedupTest {

    @Test
    @DisplayName("Record and exists works")
    void recordAndExists_works() {
        var dedup = new InMemoryPersistentDedup();
        var key = new EventKey("evt-001", "RIDE_COMPLETED");

        dedup.record(key, Instant.now());

        assertThat(dedup.exists(key)).isTrue();
    }

    @Test
    @DisplayName("Non-existent key returns false")
    void exists_nonExistentKey_returnsFalse() {
        var dedup = new InMemoryPersistentDedup();

        assertThat(dedup.exists(new EventKey("missing", "TYPE"))).isFalse();
    }

    @Test
    @DisplayName("Size tracks recorded entries")
    void size_tracksRecordedEntries() {
        var dedup = new InMemoryPersistentDedup();

        assertThat(dedup.size()).isZero();

        dedup.record(new EventKey("evt-1", "TYPE"), Instant.now());
        assertThat(dedup.size()).isEqualTo(1);

        dedup.record(new EventKey("evt-2", "TYPE"), Instant.now());
        assertThat(dedup.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Duplicate record overwrites without increasing size")
    void duplicateRecord_doesNotIncreaseSize() {
        var dedup = new InMemoryPersistentDedup();
        var key = new EventKey("evt-001", "TYPE");

        dedup.record(key, Instant.now());
        dedup.record(key, Instant.now());

        assertThat(dedup.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("Thread safety: concurrent record and exists")
    void threadSafety_concurrentRecordAndExists() throws Exception {
        var dedup = new InMemoryPersistentDedup();
        int threads = 8;
        int opsPerThread = 1000;
        var errors = new AtomicInteger(0);
        var latch = new CountDownLatch(threads);

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < opsPerThread; i++) {
                            var key = new EventKey("t" + threadId + "-evt-" + i, "TYPE");
                            dedup.record(key, Instant.now());
                            dedup.exists(key);
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                    latch.countDown();
                });
            }
            latch.await();
        }

        assertThat(errors.get()).isZero();
        assertThat(dedup.size()).isEqualTo(threads * opsPerThread);
    }
}
