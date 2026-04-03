package triggerflow.common;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Core event record representing a single occurrence in the TriggerFlow pipeline.
 *
 * <p>Events are immutable value objects that flow through the system from ingestion
 * to action execution. The payload is defensively copied on construction to prevent
 * external mutation.
 *
 * @param eventId       unique identifier for this event instance
 * @param eventType     the type of event (e.g. "RIDE_COMPLETED")
 * @param userId        the user who triggered the event
 * @param payload       arbitrary key-value data associated with the event
 * @param timestamp     when the event occurred (defaults to now if null)
 * @param sourceService the upstream service that produced the event
 */
public record TriggerFlowEvent(
        String eventId,
        String eventType,
        String userId,
        Map<String, Object> payload,
        Instant timestamp,
        String sourceService
) {

    public TriggerFlowEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(userId, "userId");
        payload = payload != null
                ? Collections.unmodifiableMap(new HashMap<>(payload))
                : Map.of();
        if (timestamp == null) timestamp = Instant.now();
    }
}
