package triggerflow.campaign;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic A/B test variant router.
 *
 * <p>Uses a hash of the userId to assign a stable 0-9999 bucket, then walks
 * through variant cumulative proportions to determine assignment. The same
 * userId + same config always produces the same variant.</p>
 */
public class ABTestRouter {

    private static final int BUCKET_COUNT = 10_000;

    /**
     * Returns the variant for the given userId based on the A/B test configuration.
     *
     * @param userId the user identifier used for deterministic hashing
     * @param config the A/B test configuration with variant proportions
     * @return the assigned variant name
     * @throws IllegalArgumentException if config has no variants
     */
    public String getVariant(String userId, ABTestConfig config) {
        Map<String, Double> variants = config.variants();
        if (variants.isEmpty()) {
            throw new IllegalArgumentException("ABTestConfig has no variants");
        }

        // Spread bits to avoid clustering with sequential userIds
        int bucket = (spreadHash(userId.hashCode()) & 0x7FFF_FFFF) % BUCKET_COUNT;

        // Build sorted list for deterministic iteration order
        List<Map.Entry<String, Double>> entries = new ArrayList<>(variants.entrySet());
        entries.sort(Map.Entry.comparingByKey());

        double cumulative = 0.0;
        for (Map.Entry<String, Double> entry : entries) {
            cumulative += entry.getValue();
            int threshold = (int) (cumulative * BUCKET_COUNT);
            if (bucket < threshold) {
                return entry.getKey();
            }
        }

        // Fallback to last variant (handles floating-point rounding)
        return entries.getLast().getKey();
    }

    /** Murmur-style bit spread to reduce clustering from sequential hashCodes. */
    private static int spreadHash(int h) {
        h ^= (h >>> 16);
        h *= 0x85ebca6b;
        h ^= (h >>> 13);
        h *= 0xc2b2ae35;
        h ^= (h >>> 16);
        return h;
    }
}
