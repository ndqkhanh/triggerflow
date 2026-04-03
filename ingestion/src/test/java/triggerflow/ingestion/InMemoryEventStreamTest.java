package triggerflow.ingestion;

import org.junit.jupiter.api.Test;
import triggerflow.common.EventKey;
import triggerflow.common.TriggerFlowEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InMemoryEventStream}.
 */
class InMemoryEventStreamTest {

    private TriggerFlowEvent event(String id, String type) {
        return new TriggerFlowEvent(id, type, "user-1", Map.of(), Instant.now(), "test-service");
    }

    @Test
    void publishAndPoll_returnsEvent() {
        InMemoryEventStream stream = new InMemoryEventStream(0);
        TriggerFlowEvent e = event("e1", "ORDER_PLACED");

        stream.publish(e);
        List<TriggerFlowEvent> events = stream.poll(Duration.ofMillis(100));

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().eventId()).isEqualTo("e1");
    }

    @Test
    void pollOnEmpty_returnsEmptyList() {
        InMemoryEventStream stream = new InMemoryEventStream(0);

        List<TriggerFlowEvent> events = stream.poll(Duration.ofMillis(100));

        assertThat(events).isEmpty();
    }

    @Test
    void multipleEvents_polledInOrder() {
        InMemoryEventStream stream = new InMemoryEventStream(0);
        stream.publish(event("e1", "A"));
        stream.publish(event("e2", "B"));
        stream.publish(event("e3", "C"));

        List<TriggerFlowEvent> events = stream.poll(Duration.ofMillis(100));

        assertThat(events).hasSize(3);
        assertThat(events.get(0).eventId()).isEqualTo("e1");
        assertThat(events.get(1).eventId()).isEqualTo("e2");
        assertThat(events.get(2).eventId()).isEqualTo("e3");
    }

    @Test
    void commit_tracksKeys() {
        InMemoryEventStream stream = new InMemoryEventStream(0);
        EventKey key = new EventKey("e1", "ORDER_PLACED");

        stream.commit(key);

        assertThat(stream.committedKeys()).containsExactly(key);
    }

    @Test
    void commit_multipleKeys() {
        InMemoryEventStream stream = new InMemoryEventStream(0);
        EventKey k1 = new EventKey("e1", "A");
        EventKey k2 = new EventKey("e2", "B");

        stream.commit(k1);
        stream.commit(k2);

        assertThat(stream.committedKeys()).containsExactlyInAnyOrder(k1, k2);
    }

    @Test
    void partition_returnsConfiguredNumber() {
        InMemoryEventStream stream = new InMemoryEventStream(7);

        assertThat(stream.partition()).isEqualTo(7);
    }

    @Test
    void publishAll_addsAllEvents() {
        InMemoryEventStream stream = new InMemoryEventStream(0);
        List<TriggerFlowEvent> events = List.of(
                event("e1", "A"),
                event("e2", "B"),
                event("e3", "C")
        );

        stream.publishAll(events);
        List<TriggerFlowEvent> polled = stream.poll(Duration.ofMillis(100));

        assertThat(polled).hasSize(3);
    }

    @Test
    void pollDrainsUpTo100Events() {
        InMemoryEventStream stream = new InMemoryEventStream(0);
        for (int i = 0; i < 150; i++) {
            stream.publish(event("e" + i, "TYPE"));
        }

        List<TriggerFlowEvent> first = stream.poll(Duration.ofMillis(100));
        assertThat(first).hasSize(100);

        List<TriggerFlowEvent> second = stream.poll(Duration.ofMillis(100));
        assertThat(second).hasSize(50);
    }

    @Test
    void committedKeys_emptyByDefault() {
        InMemoryEventStream stream = new InMemoryEventStream(0);

        assertThat(stream.committedKeys()).isEmpty();
    }

    @Test
    void commit_duplicateKey_noDoubleEntry() {
        InMemoryEventStream stream = new InMemoryEventStream(0);
        EventKey key = new EventKey("e1", "A");

        stream.commit(key);
        stream.commit(key);

        assertThat(stream.committedKeys()).hasSize(1);
    }
}
