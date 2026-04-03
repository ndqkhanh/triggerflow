package triggerflow.dedup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import triggerflow.common.EventKey;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DeduplicationFilterTest {

    private BloomPrefilter bloom;
    private EventCache cache;
    private InMemoryPersistentDedup persistent;
    private DeduplicationFilter filter;

    private static final Duration TTL = Duration.ofMinutes(5);

    @BeforeEach
    void setUp() {
        bloom = BloomPrefilter.create(10_000, 0.001);
        cache = new EventCache(10_000);
        persistent = new InMemoryPersistentDedup();
        filter = new DeduplicationFilter(bloom, cache, persistent, TTL);
    }

    @Test
    @DisplayName("New event passes all three tiers and returns NEW_EVENT")
    void check_newEvent_returnsNewEvent() {
        var key = new EventKey("evt-001", "RIDE_COMPLETED");

        var result = filter.check(key);

        assertThat(result).isEqualTo(DeduplicationResult.NEW_EVENT);
    }

    @Test
    @DisplayName("Duplicate in cache returns DUPLICATE_CACHED")
    void check_duplicateInCache_returnsDuplicateCached() {
        var key = new EventKey("evt-001", "RIDE_COMPLETED");

        // Record the event (adds to bloom and cache)
        filter.recordProcessed(key, false);

        var result = filter.check(key);

        assertThat(result).isEqualTo(DeduplicationResult.DUPLICATE_CACHED);
    }

    @Test
    @DisplayName("Duplicate in persistent store returns DUPLICATE_PERSISTENT")
    void check_duplicateInPersistent_returnsDuplicatePersistent() {
        var key = new EventKey("evt-001", "RIDE_COMPLETED");

        // Add to bloom and persistent, but NOT to cache
        bloom.add(key);
        persistent.record(key, java.time.Instant.now());

        var result = filter.check(key);

        assertThat(result).isEqualTo(DeduplicationResult.DUPLICATE_PERSISTENT);
    }

    @Test
    @DisplayName("Bloom filter negative skips cache and persistent lookups")
    void check_bloomNegative_skipsOtherTiers() {
        var key = new EventKey("evt-001", "RIDE_COMPLETED");

        // Put directly in cache and persistent WITHOUT adding to bloom
        cache.put(key, System.currentTimeMillis(), TTL);
        persistent.record(key, java.time.Instant.now());

        // Bloom says "definitely not seen" -> should return NEW_EVENT
        var result = filter.check(key);

        assertThat(result).isEqualTo(DeduplicationResult.NEW_EVENT);
    }

    @Test
    @DisplayName("recordProcessed adds to bloom and cache")
    void recordProcessed_addsToBloomAndCache() {
        var key = new EventKey("evt-001", "RIDE_COMPLETED");

        filter.recordProcessed(key, false);

        assertThat(bloom.mightContain(key)).isTrue();
        assertThat(cache.exists(key)).isTrue();
    }

    @Test
    @DisplayName("recordProcessed with actionTriggered=true also adds to persistent")
    void recordProcessed_actionTriggered_addsToPersistent() {
        var key = new EventKey("evt-001", "RIDE_COMPLETED");

        filter.recordProcessed(key, true);

        assertThat(bloom.mightContain(key)).isTrue();
        assertThat(cache.exists(key)).isTrue();
        assertThat(persistent.exists(key)).isTrue();
    }

    @Test
    @DisplayName("recordProcessed with actionTriggered=false does NOT add to persistent")
    void recordProcessed_noAction_doesNotAddToPersistent() {
        var key = new EventKey("evt-001", "RIDE_COMPLETED");

        filter.recordProcessed(key, false);

        assertThat(bloom.mightContain(key)).isTrue();
        assertThat(cache.exists(key)).isTrue();
        assertThat(persistent.exists(key)).isFalse();
    }

    @Test
    @DisplayName("End-to-end: process event then check again returns DUPLICATE_CACHED")
    void endToEnd_processAndRecheck() {
        var key = new EventKey("evt-001", "PAYMENT_SUCCEEDED");

        // First check: new event
        assertThat(filter.check(key)).isEqualTo(DeduplicationResult.NEW_EVENT);

        // Record it
        filter.recordProcessed(key, true);

        // Second check: should be cached
        assertThat(filter.check(key)).isEqualTo(DeduplicationResult.DUPLICATE_CACHED);
    }

    @Test
    @DisplayName("Metrics tracking: new events counted")
    void metrics_newEventsCounted() {
        filter.check(new EventKey("evt-1", "TYPE"));
        filter.check(new EventKey("evt-2", "TYPE"));

        assertThat(filter.getNewEvents()).isEqualTo(2);
    }

    @Test
    @DisplayName("Metrics tracking: cached duplicates counted")
    void metrics_cachedDuplicatesCounted() {
        var key = new EventKey("evt-001", "TYPE");
        filter.recordProcessed(key, false);

        filter.check(key);
        filter.check(key);

        assertThat(filter.getDuplicatesCached()).isEqualTo(2);
    }

    @Test
    @DisplayName("Metrics tracking: persistent duplicates counted")
    void metrics_persistentDuplicatesCounted() {
        var key = new EventKey("evt-001", "TYPE");
        bloom.add(key);
        persistent.record(key, java.time.Instant.now());

        filter.check(key);

        assertThat(filter.getDuplicatesPersistent()).isEqualTo(1);
    }

    @Test
    @DisplayName("10 duplicate events produce only 1 NEW_EVENT")
    void tenDuplicates_onlyOneNewEvent() {
        var key = new EventKey("evt-001", "REWARD_CLAIMED");

        // First check: new
        var first = filter.check(key);
        assertThat(first).isEqualTo(DeduplicationResult.NEW_EVENT);

        // Record it
        filter.recordProcessed(key, true);

        // Next 9 checks: all duplicates
        for (int i = 0; i < 9; i++) {
            var result = filter.check(key);
            assertThat(result).isNotEqualTo(DeduplicationResult.NEW_EVENT);
        }

        assertThat(filter.getNewEvents()).isEqualTo(1);
        assertThat(filter.getDuplicatesCached()).isEqualTo(9);
    }

    @Test
    @DisplayName("Multiple distinct events are all NEW_EVENT")
    void multipleDistinctEvents_allNew() {
        for (int i = 0; i < 100; i++) {
            var result = filter.check(new EventKey("evt-" + i, "TYPE"));
            assertThat(result).isEqualTo(DeduplicationResult.NEW_EVENT);
        }

        assertThat(filter.getNewEvents()).isEqualTo(100);
        assertThat(filter.getDuplicatesCached()).isZero();
        assertThat(filter.getDuplicatesPersistent()).isZero();
    }

    @Test
    @DisplayName("Bloom false positive falls through to NEW_EVENT when not in cache or persistent")
    void bloomFalsePositive_returnsNewEvent() {
        // Manually set bloom bits to simulate a false positive scenario
        // We add a different key to the bloom
        bloom.add(new EventKey("other-key", "TYPE"));

        // Check a key that's not actually in the bloom (unless false positive)
        var key = new EventKey("unrelated-key", "TYPE");
        var result = filter.check(key);

        // Whether bloom returns true (false positive) or false,
        // the final result should be NEW_EVENT since it's not in cache or persistent
        assertThat(result).isEqualTo(DeduplicationResult.NEW_EVENT);
    }
}
