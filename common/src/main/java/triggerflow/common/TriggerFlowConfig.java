package triggerflow.common;

import java.time.Duration;

/**
 * Immutable configuration for the TriggerFlow pipeline.
 *
 * <p>All parameters have sensible defaults obtainable via {@link #defaults()}.
 * Custom configurations can be created directly through the canonical constructor.
 *
 * @param consumerParallelism      number of parallel event consumers (default 4)
 * @param dedupWindow              time window for deduplication (default 24 hours)
 * @param cacheMaxSize             maximum entries in the in-memory dedup cache (default 1,000,000)
 * @param bloomFalsePositiveRate   target false positive rate for the Bloom pre-filter (default 0.001)
 * @param bloomExpectedInsertions  expected number of unique events for Bloom sizing (default 1,000,000)
 */
public record TriggerFlowConfig(
        int consumerParallelism,
        Duration dedupWindow,
        int cacheMaxSize,
        double bloomFalsePositiveRate,
        int bloomExpectedInsertions
) {

    /**
     * Returns a configuration with production-sensible defaults.
     *
     * @return default configuration
     */
    public static TriggerFlowConfig defaults() {
        return new TriggerFlowConfig(
                4,
                Duration.ofHours(24),
                1_000_000,
                0.001,
                1_000_000
        );
    }
}
