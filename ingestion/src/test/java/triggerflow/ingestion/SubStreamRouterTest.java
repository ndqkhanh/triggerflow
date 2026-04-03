package triggerflow.ingestion;

import org.junit.jupiter.api.Test;
import triggerflow.common.TriggerFlowEvent;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SubStreamRouter}.
 */
class SubStreamRouterTest {

    private TriggerFlowEvent event(String id, String type) {
        return new TriggerFlowEvent(id, type, "user-1", Map.of(), Instant.now(), "test-service");
    }

    @Test
    void registeredHandler_receivesMatchingEvents() {
        SubStreamRouter router = new SubStreamRouter();
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();

        router.register("ORDER_PLACED", e -> received.add(e.eventId()));
        router.route(event("e1", "ORDER_PLACED"));

        assertThat(received).containsExactly("e1");
    }

    @Test
    void unregisteredEventType_skipped() {
        SubStreamRouter router = new SubStreamRouter();
        AtomicInteger count = new AtomicInteger();

        router.register("ORDER_PLACED", e -> count.incrementAndGet());
        router.route(event("e1", "RIDE_COMPLETED"));

        assertThat(count.get()).isEqualTo(0);
    }

    @Test
    void multipleHandlers_forSameType_allCalled() {
        SubStreamRouter router = new SubStreamRouter();
        AtomicInteger handler1 = new AtomicInteger();
        AtomicInteger handler2 = new AtomicInteger();

        router.register("ORDER_PLACED", e -> handler1.incrementAndGet());
        router.register("ORDER_PLACED", e -> handler2.incrementAndGet());
        router.route(event("e1", "ORDER_PLACED"));

        assertThat(handler1.get()).isEqualTo(1);
        assertThat(handler2.get()).isEqualTo(1);
    }

    @Test
    void multipleEventTypes_correctRouting() {
        SubStreamRouter router = new SubStreamRouter();
        CopyOnWriteArrayList<String> orderEvents = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<String> rideEvents = new CopyOnWriteArrayList<>();

        router.register("ORDER_PLACED", e -> orderEvents.add(e.eventId()));
        router.register("RIDE_COMPLETED", e -> rideEvents.add(e.eventId()));

        router.route(event("e1", "ORDER_PLACED"));
        router.route(event("e2", "RIDE_COMPLETED"));
        router.route(event("e3", "ORDER_PLACED"));

        assertThat(orderEvents).containsExactly("e1", "e3");
        assertThat(rideEvents).containsExactly("e2");
    }

    @Test
    void eventsRouted_counterAccurate() {
        SubStreamRouter router = new SubStreamRouter();
        router.register("A", e -> {});

        router.route(event("e1", "A"));
        router.route(event("e2", "A"));

        assertThat(router.getEventsRouted()).isEqualTo(2);
    }

    @Test
    void eventsSkipped_counterAccurate() {
        SubStreamRouter router = new SubStreamRouter();
        router.register("A", e -> {});

        router.route(event("e1", "B"));
        router.route(event("e2", "C"));

        assertThat(router.getEventsSkipped()).isEqualTo(2);
    }

    @Test
    void noHandlersRegistered_allSkipped() {
        SubStreamRouter router = new SubStreamRouter();

        router.route(event("e1", "A"));
        router.route(event("e2", "B"));

        assertThat(router.getEventsSkipped()).isEqualTo(2);
        assertThat(router.getEventsRouted()).isEqualTo(0);
    }

    @Test
    void mixedRoutedAndSkipped_countersAccurate() {
        SubStreamRouter router = new SubStreamRouter();
        router.register("A", e -> {});

        router.route(event("e1", "A"));
        router.route(event("e2", "B"));
        router.route(event("e3", "A"));
        router.route(event("e4", "C"));

        assertThat(router.getEventsRouted()).isEqualTo(2);
        assertThat(router.getEventsSkipped()).isEqualTo(2);
    }

    @Test
    void registerAfterRouting_newHandlerReceivesFutureEvents() {
        SubStreamRouter router = new SubStreamRouter();
        AtomicInteger count = new AtomicInteger();

        // Route before any handler is registered
        router.route(event("e1", "A"));

        router.register("A", e -> count.incrementAndGet());

        // Route after handler registered
        router.route(event("e2", "A"));

        assertThat(count.get()).isEqualTo(1);
    }

    @Test
    void handlerReceivesCorrectEventData() {
        SubStreamRouter router = new SubStreamRouter();
        CopyOnWriteArrayList<TriggerFlowEvent> received = new CopyOnWriteArrayList<>();

        router.register("ORDER_PLACED", received::add);

        TriggerFlowEvent original = event("e1", "ORDER_PLACED");
        router.route(original);

        assertThat(received).hasSize(1);
        assertThat(received.getFirst().eventId()).isEqualTo("e1");
        assertThat(received.getFirst().eventType()).isEqualTo("ORDER_PLACED");
    }
}
