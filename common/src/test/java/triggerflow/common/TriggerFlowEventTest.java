package triggerflow.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TriggerFlowEventTest {

    @Test
    @DisplayName("Construction with all fields preserves values")
    void construction_allFields_preservesValues() {
        var payload = Map.of("amount", (Object) 42);
        var ts = Instant.parse("2025-01-15T10:30:00Z");

        var event = new TriggerFlowEvent(
                "evt-001", "RIDE_COMPLETED", "user-1", payload, ts, "ride-service");

        assertThat(event.eventId()).isEqualTo("evt-001");
        assertThat(event.eventType()).isEqualTo("RIDE_COMPLETED");
        assertThat(event.userId()).isEqualTo("user-1");
        assertThat(event.payload()).containsEntry("amount", 42);
        assertThat(event.timestamp()).isEqualTo(ts);
        assertThat(event.sourceService()).isEqualTo("ride-service");
    }

    @Test
    @DisplayName("Null eventId throws NullPointerException")
    void construction_nullEventId_throws() {
        assertThatThrownBy(() ->
                new TriggerFlowEvent(null, "TYPE", "user", Map.of(), Instant.now(), "svc"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    @DisplayName("Null eventType throws NullPointerException")
    void construction_nullEventType_throws() {
        assertThatThrownBy(() ->
                new TriggerFlowEvent("id", null, "user", Map.of(), Instant.now(), "svc"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("eventType");
    }

    @Test
    @DisplayName("Null userId throws NullPointerException")
    void construction_nullUserId_throws() {
        assertThatThrownBy(() ->
                new TriggerFlowEvent("id", "TYPE", null, Map.of(), Instant.now(), "svc"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("userId");
    }

    @Test
    @DisplayName("Payload is defensively copied and immutable")
    void construction_payload_isDefensivelyCopied() {
        var mutable = new HashMap<String, Object>();
        mutable.put("key", "value");

        var event = new TriggerFlowEvent("id", "TYPE", "user", mutable, Instant.now(), "svc");

        // Mutating the original map does not affect the event
        mutable.put("key2", "value2");
        assertThat(event.payload()).doesNotContainKey("key2");

        // The event's payload is unmodifiable
        assertThatThrownBy(() -> event.payload().put("new", "entry"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Null timestamp defaults to current time")
    void construction_nullTimestamp_defaultsToNow() {
        var before = Instant.now();
        var event = new TriggerFlowEvent("id", "TYPE", "user", null, null, "svc");
        var after = Instant.now();

        assertThat(event.timestamp()).isNotNull();
        assertThat(event.timestamp()).isBetween(before, after);
    }

    @Test
    @DisplayName("Null payload defaults to empty map")
    void construction_nullPayload_defaultsToEmptyMap() {
        var event = new TriggerFlowEvent("id", "TYPE", "user", null, Instant.now(), "svc");

        assertThat(event.payload()).isNotNull();
        assertThat(event.payload()).isEmpty();
    }
}
