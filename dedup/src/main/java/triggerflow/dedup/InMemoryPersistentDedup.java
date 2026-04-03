package triggerflow.dedup;

import triggerflow.common.EventKey;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link PersistentDedup} for testing.
 *
 * <p>Backed by a {@link ConcurrentHashMap}, providing thread-safe operations
 * without external infrastructure. Not suitable for production use since data
 * is lost on process restart.
 */
public class InMemoryPersistentDedup implements PersistentDedup {

    private final ConcurrentHashMap<EventKey, Instant> store = new ConcurrentHashMap<>();

    @Override
    public boolean exists(EventKey key) {
        return store.containsKey(key);
    }

    @Override
    public void record(EventKey key, Instant processedAt) {
        store.put(key, processedAt);
    }

    @Override
    public int size() {
        return store.size();
    }
}
