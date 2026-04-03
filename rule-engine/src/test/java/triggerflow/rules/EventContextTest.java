package triggerflow.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import triggerflow.common.TriggerFlowEvent;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EventContext")
class EventContextTest {

    private static final TriggerFlowEvent SAMPLE_EVENT = new TriggerFlowEvent(
            "evt-001",
            "RIDE_COMPLETED",
            "user-42",
            Map.of(
                    "country", "VN",
                    "amount", 150.0,
                    "address", Map.of("city", "HCMC", "zip", "70000")
            ),
            Instant.parse("2025-01-15T10:00:00Z"),
            "ride-service"
    );

    private static final Map<String, Object> ENRICHED = Map.of(
            "tier", "gold",
            "fraud_score", 0.05
    );

    private final EventContext ctx = new EventContext(SAMPLE_EVENT, ENRICHED);

    @Test
    @DisplayName("resolveField 'payload.country' returns payload value")
    void resolveField_payloadCountry_returnsValue() {
        assertThat(ctx.resolveField("payload.country")).isEqualTo("VN");
    }

    @Test
    @DisplayName("resolveField 'event.userId' returns userId")
    void resolveField_eventUserId_returnsUserId() {
        assertThat(ctx.resolveField("event.userId")).isEqualTo("user-42");
    }

    @Test
    @DisplayName("resolveField 'event.eventType' returns eventType")
    void resolveField_eventEventType_returnsEventType() {
        assertThat(ctx.resolveField("event.eventType")).isEqualTo("RIDE_COMPLETED");
    }

    @Test
    @DisplayName("resolveField 'enriched.tier' returns enriched data")
    void resolveField_enrichedTier_returnsEnrichedValue() {
        assertThat(ctx.resolveField("enriched.tier")).isEqualTo("gold");
    }

    @Test
    @DisplayName("resolveField 'external.fraud_score' returns enriched data via external prefix")
    void resolveField_externalFraudScore_returnsEnrichedValue() {
        assertThat(ctx.resolveField("external.fraud_score")).isEqualTo(0.05);
    }

    @Test
    @DisplayName("resolveField for missing field returns null")
    void resolveField_missingField_returnsNull() {
        assertThat(ctx.resolveField("payload.nonexistent")).isNull();
        assertThat(ctx.resolveField("event.unknown")).isNull();
        assertThat(ctx.resolveField("unknown.prefix")).isNull();
    }

    @Test
    @DisplayName("resolveField 'payload.address.city' resolves nested map")
    void resolveField_nestedPayload_resolvesNestedMap() {
        assertThat(ctx.resolveField("payload.address.city")).isEqualTo("HCMC");
    }

    @Test
    @DisplayName("single-segment field resolves from payload first, then enriched")
    void resolveField_singleSegment_resolvesFromPayload() {
        // "country" exists in payload
        assertThat(ctx.resolveField("country")).isEqualTo("VN");
        // "tier" exists only in enriched
        assertThat(ctx.resolveField("tier")).isEqualTo("gold");
        // "missing" exists nowhere
        assertThat(ctx.resolveField("missing")).isNull();
    }
}
