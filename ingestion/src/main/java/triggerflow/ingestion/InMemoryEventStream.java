package triggerflow.ingestion;

import triggerflow.common.EventKey;
import triggerflow.common.TriggerFlowEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * In-memory test double for {@link EventStream}.
 *
 * <p>Backed by a {@link ConcurrentLinkedQueue} for thread-safe publish/poll.
 * Committed keys are tracked in a concurrent set for test verification.
 */
public class InMemoryEventStream implements EventStream {

    private static final int MAX_POLL_BATCH = 100;

    private final int partitionNumber;
    private final ConcurrentLinkedQueue<TriggerFlowEvent> queue = new ConcurrentLinkedQueue<>();
    private final Set<EventKey> committed = ConcurrentHashMap.newKeySet();

    /**
     * @param partitionNumber the partition number this stream represents
     */
    public InMemoryEventStream(int partitionNumber) {
        this.partitionNumber = partitionNumber;
    }

    /**
     * Publishes a single event to this stream.
     */
    public void publish(TriggerFlowEvent event) {
        queue.add(event);
    }

    /**
     * Publishes multiple events to this stream.
     */
    public void publishAll(List<TriggerFlowEvent> events) {
        queue.addAll(events);
    }

    @Override
    public List<TriggerFlowEvent> poll(Duration timeout) {
        List<TriggerFlowEvent> batch = new ArrayList<>();
        for (int i = 0; i < MAX_POLL_BATCH; i++) {
            TriggerFlowEvent event = queue.poll();
            if (event == null) break;
            batch.add(event);
        }
        return Collections.unmodifiableList(batch);
    }

    @Override
    public void commit(EventKey key) {
        committed.add(key);
    }

    @Override
    public int partition() {
        return partitionNumber;
    }

    /**
     * Returns the set of committed event keys, for test verification.
     */
    public Set<EventKey> committedKeys() {
        return Collections.unmodifiableSet(committed);
    }
}
