package triggerflow.dedup;

import triggerflow.common.EventKey;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Bloom filter for probabilistic deduplication pre-checking of {@link EventKey}s.
 *
 * <p>A Bloom filter answers "has this event been seen before?" with:
 * <ul>
 *   <li><b>Definite NO</b> -- if {@link #mightContain} returns false, the event was
 *       never recorded. This allows the dedup pipeline to skip expensive cache and
 *       persistent store lookups.</li>
 *   <li><b>Probable YES</b> -- if it returns true, the event was probably recorded,
 *       with a bounded false positive rate.</li>
 * </ul>
 *
 * <h2>Memory layout</h2>
 * The filter is backed by a {@code long[]} bit array accessed through
 * {@link AtomicLongArray} for lock-free thread safety. Each {@code long} stores
 * 64 bits, so an {@code m}-bit filter requires {@code ceil(m/64)} longs.
 *
 * <h2>Optimal parameters</h2>
 * Given expected insertions {@code n} and desired false-positive rate {@code p}:
 * <pre>
 *   m = -n * ln(p) / (ln 2)^2   (number of bits)
 *   k = (m / n) * ln 2           (number of hash functions)
 * </pre>
 *
 * <h2>Double hashing (Kirsch-Mitzenmacher)</h2>
 * Rather than maintaining {@code k} independent hash functions, we use:
 * <pre>
 *   h_i(x) = (h1(x) + i * h2(x)) mod m
 * </pre>
 * where {@code h1} and {@code h2} are derived from two MurmurHash3 (32-bit)
 * invocations with different seeds.
 *
 * <h2>Thread safety</h2>
 * {@link AtomicLongArray} with CAS loops for setBit ensures concurrent adds
 * never corrupt the bit array. The element count uses {@link AtomicInteger}.
 */
public class BloomPrefilter {

    private static final double LN2 = Math.log(2.0);
    private static final double LN2_SQUARED = LN2 * LN2;

    private final int m;                    // total number of bits
    private final int k;                    // number of hash positions per element
    private final AtomicLongArray bits;     // bit array, each long holds 64 bits
    private final AtomicInteger count;      // number of elements added

    /**
     * Constructs a Bloom pre-filter optimised for the given capacity and error rate.
     *
     * @param expectedInsertions expected number of distinct events ({@code n})
     * @param falsePositiveRate  desired false positive probability (e.g. 0.001 for 0.1%)
     * @throws IllegalArgumentException if parameters are out of range
     */
    public BloomPrefilter(int expectedInsertions, double falsePositiveRate) {
        if (expectedInsertions <= 0) {
            throw new IllegalArgumentException(
                    "expectedInsertions must be positive, got " + expectedInsertions);
        }
        if (falsePositiveRate <= 0.0 || falsePositiveRate >= 1.0) {
            throw new IllegalArgumentException(
                    "falsePositiveRate must be in (0,1), got " + falsePositiveRate);
        }

        this.m = optimalBitCount(expectedInsertions, falsePositiveRate);
        this.k = optimalHashCount(m, expectedInsertions);

        int words = (m + 63) / 64; // ceil(m / 64)
        this.bits = new AtomicLongArray(words);
        this.count = new AtomicInteger(0);
    }

    // package-private for test verification
    int bitCount() { return m; }
    int hashCount() { return k; }

    /**
     * Records an {@link EventKey} in the filter.
     *
     * <p>Sets {@code k} bit positions derived from the double-hash of the key.
     * This operation is thread-safe and lock-free.
     *
     * @param key the event key to add
     */
    public void add(EventKey key) {
        long combined = murmur3Hash64(toKeyString(key));
        int h1 = (int) (combined >>> 32);
        int h2 = (int) combined;

        for (int i = 0; i < k; i++) {
            // Kirsch-Mitzenmacher double-hashing: position = (h1 + i*h2) mod m
            int position = Math.floorMod(h1 + i * h2, m);
            setBit(position);
        }
        count.incrementAndGet();
    }

    /**
     * Tests whether an {@link EventKey} has been recorded in the filter.
     *
     * @param key the event key to test
     * @return {@code false} if definitely absent; {@code true} if probably present
     */
    public boolean mightContain(EventKey key) {
        long combined = murmur3Hash64(toKeyString(key));
        int h1 = (int) (combined >>> 32);
        int h2 = (int) combined;

        for (int i = 0; i < k; i++) {
            int position = Math.floorMod(h1 + i * h2, m);
            if (!getBit(position)) {
                return false; // definite negative
            }
        }
        return true; // probable positive
    }

    /**
     * Estimates the current false positive rate based on the number of inserted elements.
     *
     * <p>Formula: {@code FPR = (1 - e^(-k*n/m))^k}
     *
     * @return current estimated false positive probability in [0, 1]
     */
    public double expectedFalsePositiveRate() {
        int n = count.get();
        if (n == 0) {
            return 0.0;
        }
        double exponent = -(double) k * n / m;
        double probBitSet = 1.0 - Math.exp(exponent);
        return Math.pow(probBitSet, k);
    }

    /**
     * Returns the number of elements that have been added to the filter.
     *
     * @return number of {@link #add} invocations
     */
    public int size() {
        return count.get();
    }

    /**
     * Factory method: creates a BloomPrefilter with optimal parameters.
     *
     * @param expectedInsertions expected number of unique events
     * @param fpr                desired false positive rate
     * @return configured BloomPrefilter
     */
    public static BloomPrefilter create(int expectedInsertions, double fpr) {
        return new BloomPrefilter(expectedInsertions, fpr);
    }

    // -------------------------------------------------------------------------
    // Internal: EventKey to string conversion
    // -------------------------------------------------------------------------

    private static String toKeyString(EventKey key) {
        return key.eventId() + ":" + key.eventType();
    }

    // -------------------------------------------------------------------------
    // Bit array operations (lock-free via CAS loop)
    // -------------------------------------------------------------------------

    private void setBit(int position) {
        int word = position >>> 6;          // position / 64
        long mask = 1L << (position & 63);  // bit within the word
        long prev, next;
        do {
            prev = bits.get(word);
            next = prev | mask;
            if (next == prev) return;       // already set, no-op
        } while (!bits.compareAndSet(word, prev, next));
    }

    private boolean getBit(int position) {
        int word = position >>> 6;
        long mask = 1L << (position & 63);
        return (bits.get(word) & mask) != 0;
    }

    // -------------------------------------------------------------------------
    // MurmurHash3 -- 32-bit finalised, applied twice with different seeds
    // to produce a 64-bit result for double-hashing.
    //
    // MurmurHash3 was created by Austin Appleby. The algorithm is public domain.
    // Reference: https://github.com/aappleby/smhasher/blob/master/src/MurmurHash3.cpp
    // -------------------------------------------------------------------------

    /**
     * Computes a 64-bit hash by combining two independent MurmurHash3 (32-bit)
     * invocations with seeds 0 and 0x9747b28c.
     */
    static long murmur3Hash64(String input) {
        byte[] data = input.getBytes(StandardCharsets.UTF_8);
        long h1 = murmur3_32(data, 0) & 0xFFFFFFFFL;
        long h2 = murmur3_32(data, 0x9747b28c) & 0xFFFFFFFFL;
        return (h1 << 32) | h2;
    }

    /**
     * MurmurHash3 32-bit implementation from scratch.
     *
     * <p>Processes input in 4-byte blocks with constants c1=0xcc9e2d51 and
     * c2=0x1b873593 chosen to maximise avalanche. Remaining 1-3 byte tail
     * is handled separately, followed by finalisation (fmix32).
     */
    static int murmur3_32(byte[] data, int seed) {
        final int c1 = 0xcc9e2d51;
        final int c2 = 0x1b873593;

        int h = seed;
        int len = data.length;
        int numBlocks = len / 4;

        // body: process 4-byte blocks
        for (int i = 0; i < numBlocks; i++) {
            int k = readLittleEndianInt(data, i * 4);

            k *= c1;
            k = Integer.rotateLeft(k, 15);
            k *= c2;

            h ^= k;
            h = Integer.rotateLeft(h, 13);
            h = h * 5 + 0xe6546b64;
        }

        // tail: handle remaining 1-3 bytes
        int tail = numBlocks * 4;
        int k1 = 0;
        switch (len & 3) {
            case 3: k1 ^= (data[tail + 2] & 0xFF) << 16; // fall through
            case 2: k1 ^= (data[tail + 1] & 0xFF) << 8;  // fall through
            case 1: k1 ^= (data[tail] & 0xFF);
                k1 *= c1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= c2;
                h ^= k1;
        }

        // finalisation
        h ^= len;
        h = fmix32(h);
        return h;
    }

    /**
     * MurmurHash3 finalisation mix -- ensures all bits of {@code h} affect all output bits.
     */
    private static int fmix32(int h) {
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }

    /** Reads a 32-bit little-endian integer from {@code data} at {@code offset}. */
    private static int readLittleEndianInt(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    // -------------------------------------------------------------------------
    // Parameter calculation
    // -------------------------------------------------------------------------

    /** m = -n * ln(p) / (ln 2)^2 */
    static int optimalBitCount(int n, double p) {
        return Math.max(1, (int) Math.ceil(-n * Math.log(p) / LN2_SQUARED));
    }

    /** k = (m / n) * ln 2 */
    static int optimalHashCount(int m, int n) {
        return Math.max(1, (int) Math.round((double) m / n * LN2));
    }
}
