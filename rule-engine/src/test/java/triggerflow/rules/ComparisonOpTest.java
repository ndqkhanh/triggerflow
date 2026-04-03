package triggerflow.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ComparisonOp")
class ComparisonOpTest {

    @Nested
    @DisplayName("numeric evaluate(double, double)")
    class NumericTests {

        @Test
        @DisplayName("EQ returns true for equal doubles")
        void eq_equalDoubles_returnsTrue() {
            assertThat(ComparisonOp.EQ.evaluate(5.0, 5.0)).isTrue();
        }

        @Test
        @DisplayName("NEQ returns true for different doubles")
        void neq_differentDoubles_returnsTrue() {
            assertThat(ComparisonOp.NEQ.evaluate(5.0, 3.0)).isTrue();
        }

        @Test
        @DisplayName("GT returns true when left > right")
        void gt_leftGreater_returnsTrue() {
            assertThat(ComparisonOp.GT.evaluate(10.0, 5.0)).isTrue();
            assertThat(ComparisonOp.GT.evaluate(5.0, 10.0)).isFalse();
        }

        @Test
        @DisplayName("GTE returns true when left >= right")
        void gte_leftGreaterOrEqual_returnsTrue() {
            assertThat(ComparisonOp.GTE.evaluate(10.0, 5.0)).isTrue();
            assertThat(ComparisonOp.GTE.evaluate(5.0, 5.0)).isTrue();
            assertThat(ComparisonOp.GTE.evaluate(4.0, 5.0)).isFalse();
        }

        @Test
        @DisplayName("LT returns true when left < right")
        void lt_leftLess_returnsTrue() {
            assertThat(ComparisonOp.LT.evaluate(3.0, 5.0)).isTrue();
            assertThat(ComparisonOp.LT.evaluate(5.0, 3.0)).isFalse();
        }

        @Test
        @DisplayName("LTE returns true when left <= right")
        void lte_leftLessOrEqual_returnsTrue() {
            assertThat(ComparisonOp.LTE.evaluate(3.0, 5.0)).isTrue();
            assertThat(ComparisonOp.LTE.evaluate(5.0, 5.0)).isTrue();
            assertThat(ComparisonOp.LTE.evaluate(6.0, 5.0)).isFalse();
        }

        @Test
        @DisplayName("IN throws UnsupportedOperationException for numeric")
        void in_numeric_throws() {
            assertThatThrownBy(() -> ComparisonOp.IN.evaluate(1.0, 2.0))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("CONTAINS throws UnsupportedOperationException for numeric")
        void contains_numeric_throws() {
            assertThatThrownBy(() -> ComparisonOp.CONTAINS.evaluate(1.0, 2.0))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("string evaluate(String, String)")
    class StringTests {

        @Test
        @DisplayName("EQ is case-insensitive")
        void eq_caseInsensitive_returnsTrue() {
            assertThat(ComparisonOp.EQ.evaluate("Hello", "hello")).isTrue();
            assertThat(ComparisonOp.EQ.evaluate("Hello", "world")).isFalse();
        }

        @Test
        @DisplayName("NEQ is case-insensitive")
        void neq_caseInsensitive_returnsTrue() {
            assertThat(ComparisonOp.NEQ.evaluate("Hello", "world")).isTrue();
            assertThat(ComparisonOp.NEQ.evaluate("Hello", "hello")).isFalse();
        }

        @Test
        @DisplayName("CONTAINS checks substring match")
        void contains_substring_returnsTrue() {
            assertThat(ComparisonOp.CONTAINS.evaluate("Hello World", "World")).isTrue();
            assertThat(ComparisonOp.CONTAINS.evaluate("Hello World", "xyz")).isFalse();
        }

        @Test
        @DisplayName("IN checks comma-separated list membership")
        void in_commaSeparated_returnsTrue() {
            assertThat(ComparisonOp.IN.evaluate("VN", "VN,US,UK")).isTrue();
            assertThat(ComparisonOp.IN.evaluate("US", "VN, US, UK")).isTrue();
            assertThat(ComparisonOp.IN.evaluate("JP", "VN,US,UK")).isFalse();
        }
    }

    @Nested
    @DisplayName("dynamic evaluate(Object, Object)")
    class ObjectTests {

        @Test
        @DisplayName("dispatches to numeric when both are Numbers")
        void bothNumbers_usesNumericComparison() {
            assertThat(ComparisonOp.GT.evaluate((Object) 10, (Object) 5)).isTrue();
            assertThat(ComparisonOp.GT.evaluate((Object) 3, (Object) 5)).isFalse();
        }

        @Test
        @DisplayName("dispatches to string when not both Numbers")
        void mixedTypes_usesStringComparison() {
            assertThat(ComparisonOp.EQ.evaluate((Object) "hello", (Object) "HELLO")).isTrue();
        }

        @Test
        @DisplayName("handles null left by converting to empty string")
        void nullLeft_convertsToEmptyString() {
            Object nullLeft = null;
            Object emptyRight = "";
            assertThat(ComparisonOp.EQ.evaluate(nullLeft, emptyRight)).isTrue();
        }
    }
}
