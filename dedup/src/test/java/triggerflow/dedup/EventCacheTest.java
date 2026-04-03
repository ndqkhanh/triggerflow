package triggerflow.dedup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import triggerflow.common.EventKey;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EventCacheTest {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    @Test
    @DisplayName("Put and exists works for basic case")
    void putAndExists_basicCase() {
        var cache = new EventCache(100);
        var key = new EventKey("evt-001", "RIDE_COMPLETED");

        cache.put(key, System.currentTimeMillis(), DEFAULT_TTL);

        assertThat(cache.exists(key)).isTrue();
    }

    @Test
    @DisplayName("Non-existent key returns false")
    void exists_nonExistentKey_returnsFalse() {
        var cache = new EventCache(100);

        assertThat(cache.exists(new EventKey("missing", "TYPE"))).isFalse();
    }

    @Test
    @DisplayName("TTL expiry: entry disappears after TTL")
    void ttlExpiry_entryDisappearsAfterTtl() throws Exception {
        var cache = new EventCache(100);
        var key = new EventKey("evt-001", "TYPE");

        cache.put(key, System.currentTimeMillis(), Duration.ofMillis(100));

        assertThat(cache.exists(key)).isTrue();

        Thread.sleep(150);

        assertThat(cache.exists(key)).isFalse();
    }

    @Test
    @DisplayName("LRU eviction: oldest entry evicted when over maxSize")
    void lruEviction_oldestEvicted() {
        var cache = new EventCache(3);
        var key1 = new EventKey("evt-1", "TYPE");
        var key2 = new EventKey("evt-2", "TYPE");
        var key3 = new EventKey("evt-3", "TYPE");
        var key4 = new EventKey("evt-4", "TYPE");

        cache.put(key1, 1000, DEFAULT_TTL);
        cache.put(key2, 2000, DEFAULT_TTL);
        cache.put(key3, 3000, DEFAULT_TTL);

        // Cache is full (3/3). Adding key4 should evict key1 (LRU).
        cache.put(key4, 4000, DEFAULT_TTL);

        assertThat(cache.exists(key1)).isFalse(); // evicted
        assertThat(cache.exists(key2)).isTrue();
        assertThat(cache.exists(key3)).isTrue();
        assertThat(cache.exists(key4)).isTrue();
    }

    @Test
    @DisplayName("Access moves entry to MRU position, changing eviction order")
    void access_movesToMru() {
        var cache = new EventCache(3);
        var key1 = new EventKey("evt-1", "TYPE");
        var key2 = new EventKey("evt-2", "TYPE");
        var key3 = new EventKey("evt-3", "TYPE");
        var key4 = new EventKey("evt-4", "TYPE");

        cache.put(key1, 1000, DEFAULT_TTL);
        cache.put(key2, 2000, DEFAULT_TTL);
        cache.put(key3, 3000, DEFAULT_TTL);

        // Access key1, making it MRU. LRU order is now: key2, key3, key1
        cache.exists(key1);

        // Adding key4 should now evict key2 (the new LRU)
        cache.put(key4, 4000, DEFAULT_TTL);

        assertThat(cache.exists(key1)).isTrue();  // was accessed, now MRU
        assertThat(cache.exists(key2)).isFalse(); // LRU, evicted
        assertThat(cache.exists(key3)).isTrue();
        assertThat(cache.exists(key4)).isTrue();
    }

    @Test
    @DisplayName("Expired entries not returned by exists")
    void exists_expiredEntry_returnsFalse() throws Exception {
        var cache = new EventCache(100);
        var key = new EventKey("evt-001", "TYPE");

        cache.put(key, System.currentTimeMillis(), Duration.ofMillis(50));
        Thread.sleep(80);

        assertThat(cache.exists(key)).isFalse();
    }

    @Test
    @DisplayName("Size tracking reflects current entries")
    void size_reflectsCurrentEntries() {
        var cache = new EventCache(100);

        assertThat(cache.size()).isZero();

        cache.put(new EventKey("evt-1", "TYPE"), 1000, DEFAULT_TTL);
        assertThat(cache.size()).isEqualTo(1);

        cache.put(new EventKey("evt-2", "TYPE"), 2000, DEFAULT_TTL);
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Size decreases after LRU eviction")
    void size_decreasesAfterEviction() {
        var cache = new EventCache(2);

        cache.put(new EventKey("evt-1", "TYPE"), 1000, DEFAULT_TTL);
        cache.put(new EventKey("evt-2", "TYPE"), 2000, DEFAULT_TTL);
        assertThat(cache.size()).isEqualTo(2);

        cache.put(new EventKey("evt-3", "TYPE"), 3000, DEFAULT_TTL);
        assertThat(cache.size()).isEqualTo(2); // still 2 (one evicted, one added)
    }

    @Test
    @DisplayName("evictExpired removes all expired entries")
    void evictExpired_removesAllExpired() throws Exception {
        var cache = new EventCache(100);

        cache.put(new EventKey("evt-1", "TYPE"), 1000, Duration.ofMillis(50));
        cache.put(new EventKey("evt-2", "TYPE"), 2000, Duration.ofMillis(50));
        cache.put(new EventKey("evt-3", "TYPE"), 3000, Duration.ofHours(1));

        Thread.sleep(80);
        cache.evictExpired();

        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.exists(new EventKey("evt-3", "TYPE"))).isTrue();
    }

    @Test
    @DisplayName("Put with existing key updates the entry")
    void put_existingKey_updatesEntry() {
        var cache = new EventCache(100);
        var key = new EventKey("evt-001", "TYPE");

        cache.put(key, 1000, DEFAULT_TTL);
        cache.put(key, 2000, DEFAULT_TTL);

        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.exists(key)).isTrue();
    }

    @Test
    @DisplayName("Thread safety: concurrent put and exists")
    void threadSafety_concurrentPutAndExists() throws Exception {
        var cache = new EventCache(10_000);
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
                            cache.put(key, System.currentTimeMillis(), DEFAULT_TTL);
                            cache.exists(key);
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
        assertThat(cache.size()).isEqualTo(threads * opsPerThread);
    }

    @Test
    @DisplayName("Multiple evictions maintain consistency")
    void multipleEvictions_maintainConsistency() {
        var cache = new EventCache(2);

        for (int i = 0; i < 100; i++) {
            cache.put(new EventKey("evt-" + i, "TYPE"), i * 1000L, DEFAULT_TTL);
        }

        assertThat(cache.size()).isEqualTo(2);
        // Only the two most recently added should remain
        assertThat(cache.exists(new EventKey("evt-99", "TYPE"))).isTrue();
        assertThat(cache.exists(new EventKey("evt-98", "TYPE"))).isTrue();
    }

    @Test
    @DisplayName("evictExpired on empty cache is safe")
    void evictExpired_emptyCache_isSafe() {
        var cache = new EventCache(100);
        cache.evictExpired(); // should not throw
        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("evictExpired when nothing is expired is no-op")
    void evictExpired_nothingExpired_isNoop() {
        var cache = new EventCache(100);
        cache.put(new EventKey("evt-1", "TYPE"), 1000, Duration.ofHours(1));

        cache.evictExpired();

        assertThat(cache.size()).isEqualTo(1);
    }
}
