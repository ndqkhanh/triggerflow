package triggerflow.dedup;

import triggerflow.common.EventKey;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory deduplication cache with TTL expiry and LRU eviction.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>A {@link ConcurrentHashMap} provides O(1) key lookup without blocking readers.</li>
 *   <li>A doubly-linked list with sentinel head/tail maintains access order for O(1) LRU
 *       eviction -- the tail's predecessor is always the least recently used entry.</li>
 *   <li>A {@link ReentrantLock} protects structural modifications to the linked list
 *       (insert, remove, move-to-head) to prevent corruption under concurrency.</li>
 *   <li>TTL is checked lazily on each {@link #exists} call and can also be cleaned
 *       in bulk via {@link #evictExpired()}.</li>
 * </ul>
 *
 * <p>This follows the patterns from FlashCache's {@code CacheEngine} (TTL semantics)
 * and {@code LRUEviction} (doubly-linked list with sentinels).
 */
public class EventCache {

    /**
     * An entry in the cache, also a node in the doubly-linked list.
     */
    private static class CacheEntry {
        final EventKey key;
        final long timestampMs;
        final long expiryMs;
        CacheEntry prev;
        CacheEntry next;

        CacheEntry(EventKey key, long timestampMs, long expiryMs) {
            this.key = key;
            this.timestampMs = timestampMs;
            this.expiryMs = expiryMs;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiryMs;
        }
    }

    private final int maxSize;
    private final ConcurrentHashMap<EventKey, CacheEntry> map;
    private final ReentrantLock lock = new ReentrantLock();

    // Sentinel nodes: head.next = MRU, tail.prev = LRU
    private final CacheEntry head = new CacheEntry(null, 0, 0);
    private final CacheEntry tail = new CacheEntry(null, 0, 0);

    /**
     * Creates a new EventCache.
     *
     * @param maxSize maximum number of entries before LRU eviction kicks in
     */
    public EventCache(int maxSize) {
        this.maxSize = maxSize;
        this.map = new ConcurrentHashMap<>(Math.min(maxSize, 1024));
        head.next = tail;
        tail.prev = head;
    }

    /**
     * Checks if the given key exists in the cache and is not expired.
     *
     * <p>If found and still valid, the entry is moved to the head (most recently used).
     * If found but expired, the entry is removed (lazy expiry).
     *
     * @param key the event key to check
     * @return true if the key exists and is not expired
     */
    public boolean exists(EventKey key) {
        CacheEntry entry = map.get(key);
        if (entry == null) {
            return false;
        }
        if (entry.isExpired()) {
            // Lazy expiry: remove the expired entry
            lock.lock();
            try {
                // Re-check after acquiring lock (another thread may have removed it)
                CacheEntry current = map.get(key);
                if (current != null && current.isExpired()) {
                    removeNode(current);
                    map.remove(key);
                }
            } finally {
                lock.unlock();
            }
            return false;
        }
        // Move to head (MRU) on access
        lock.lock();
        try {
            // Re-check the entry is still in the map
            if (map.containsKey(key)) {
                moveToHead(entry);
            }
        } finally {
            lock.unlock();
        }
        return true;
    }

    /**
     * Adds or updates a key in the cache.
     *
     * <p>If the cache exceeds {@code maxSize} after insertion, the least recently
     * used entry is evicted.
     *
     * @param key         the event key
     * @param timestampMs the event timestamp in milliseconds
     * @param ttl         time-to-live for this entry
     */
    public void put(EventKey key, long timestampMs, Duration ttl) {
        long expiryMs = System.currentTimeMillis() + ttl.toMillis();
        CacheEntry entry = new CacheEntry(key, timestampMs, expiryMs);

        lock.lock();
        try {
            CacheEntry existing = map.get(key);
            if (existing != null) {
                // Replace existing entry
                removeNode(existing);
            }
            map.put(key, entry);
            insertAtHead(entry);

            // Evict LRU entries if over capacity
            while (map.size() > maxSize) {
                CacheEntry lru = tail.prev;
                if (lru == head) break; // list is empty (shouldn't happen)
                removeNode(lru);
                map.remove(lru.key);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of entries currently in the cache (including expired ones
     * that haven't been lazily removed yet).
     *
     * @return current cache size
     */
    public int size() {
        return map.size();
    }

    /**
     * Bulk removal of all expired entries.
     *
     * <p>This can be called periodically by a background scheduler to prevent
     * expired entries from accumulating between access-driven lazy evictions.
     */
    public void evictExpired() {
        lock.lock();
        try {
            // Collect keys to remove (avoid ConcurrentModificationException)
            List<EventKey> expired = new ArrayList<>();
            for (var mapEntry : map.entrySet()) {
                if (mapEntry.getValue().isExpired()) {
                    expired.add(mapEntry.getKey());
                }
            }
            for (EventKey key : expired) {
                CacheEntry entry = map.remove(key);
                if (entry != null) {
                    removeNode(entry);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    // --- linked list operations (caller must hold lock) ---

    private void insertAtHead(CacheEntry node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(CacheEntry node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void moveToHead(CacheEntry node) {
        removeNode(node);
        insertAtHead(node);
    }
}
