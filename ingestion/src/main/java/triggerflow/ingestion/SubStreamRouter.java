package triggerflow.ingestion;

import triggerflow.common.TriggerFlowEvent;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Routes events to registered handlers based on event type.
 *
 * <p>Handlers are registered per event type. When an event arrives, the router
 * looks up all handlers for that type and invokes each one. Events with no
 * matching handler are silently skipped.
 *
 * <p>Thread-safe: the handler map uses {@link ConcurrentHashMap} and each
 * handler list uses {@link CopyOnWriteArrayList}.
 */
public class SubStreamRouter {

    private final ConcurrentHashMap<String, List<Consumer<TriggerFlowEvent>>> handlers = new ConcurrentHashMap<>();

    private final AtomicLong eventsRouted = new AtomicLong();
    private final AtomicLong eventsSkipped = new AtomicLong();

    /**
     * Registers a handler for the given event type.
     *
     * @param eventType the event type to match (e.g. "RIDE_COMPLETED")
     * @param handler   callback invoked for each matching event
     */
    public void register(String eventType, Consumer<TriggerFlowEvent> handler) {
        handlers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /**
     * Routes an event to all registered handlers for its type.
     * If no handlers are registered for the event type, the event is skipped.
     *
     * @param event the event to route
     */
    public void route(TriggerFlowEvent event) {
        List<Consumer<TriggerFlowEvent>> matchedHandlers = handlers.get(event.eventType());
        if (matchedHandlers == null || matchedHandlers.isEmpty()) {
            eventsSkipped.incrementAndGet();
            return;
        }
        eventsRouted.incrementAndGet();
        for (Consumer<TriggerFlowEvent> handler : matchedHandlers) {
            handler.accept(event);
        }
    }

    /** Total number of events successfully routed to at least one handler. */
    public long getEventsRouted() {
        return eventsRouted.get();
    }

    /** Total number of events skipped because no handler was registered. */
    public long getEventsSkipped() {
        return eventsSkipped.get();
    }
}
