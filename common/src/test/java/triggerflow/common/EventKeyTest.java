package triggerflow.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventKeyTest {

    @Test
    @DisplayName("Same eventId and eventType are equal")
    void equality_sameIdAndType_areEqual() {
        var key1 = new EventKey("evt-001", "RIDE_COMPLETED");
        var key2 = new EventKey("evt-001", "RIDE_COMPLETED");

        assertThat(key1).isEqualTo(key2);
    }

    @Test
    @DisplayName("hashCode is consistent for equal keys")
    void hashCode_equalKeys_sameHashCode() {
        var key1 = new EventKey("evt-001", "RIDE_COMPLETED");
        var key2 = new EventKey("evt-001", "RIDE_COMPLETED");

        assertThat(key1.hashCode()).isEqualTo(key2.hashCode());
    }

    @Test
    @DisplayName("Different eventType means not equal")
    void equality_differentType_notEqual() {
        var key1 = new EventKey("evt-001", "RIDE_COMPLETED");
        var key2 = new EventKey("evt-001", "PAYMENT_SUCCEEDED");

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    @DisplayName("Null eventId throws NullPointerException")
    void construction_nullEventId_throws() {
        assertThatThrownBy(() -> new EventKey(null, "TYPE"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Null eventType throws NullPointerException")
    void construction_nullEventType_throws() {
        assertThatThrownBy(() -> new EventKey("id", null))
                .isInstanceOf(NullPointerException.class);
    }
}
