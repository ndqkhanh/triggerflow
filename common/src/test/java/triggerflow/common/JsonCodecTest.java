package triggerflow.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonCodecTest {

    // =========================================================================
    // Parsing tests
    // =========================================================================

    @Nested
    @DisplayName("Parse")
    class ParseTests {

        @Test
        @DisplayName("Parse simple object")
        void parseObject() {
            var result = JsonCodec.parse("{\"name\":\"Alice\",\"age\":30}");

            assertThat(result).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) result;
            assertThat(map).containsEntry("name", "Alice");
            assertThat(map).containsEntry("age", 30L);
        }

        @Test
        @DisplayName("Parse simple array")
        void parseArray() {
            var result = JsonCodec.parse("[1, 2, 3]");

            assertThat(result).isInstanceOf(List.class);
            @SuppressWarnings("unchecked")
            var list = (List<Object>) result;
            assertThat(list).containsExactly(1L, 2L, 3L);
        }

        @Test
        @DisplayName("Parse string")
        void parseString() {
            var result = JsonCodec.parse("\"hello world\"");
            assertThat(result).isEqualTo("hello world");
        }

        @Test
        @DisplayName("Parse integer number")
        void parseInteger() {
            assertThat(JsonCodec.parse("42")).isEqualTo(42L);
        }

        @Test
        @DisplayName("Parse negative number")
        void parseNegativeNumber() {
            assertThat(JsonCodec.parse("-17")).isEqualTo(-17L);
        }

        @Test
        @DisplayName("Parse decimal number")
        void parseDecimalNumber() {
            assertThat(JsonCodec.parse("3.14")).isEqualTo(3.14);
        }

        @Test
        @DisplayName("Parse boolean true and false")
        void parseBoolean() {
            assertThat(JsonCodec.parse("true")).isEqualTo(Boolean.TRUE);
            assertThat(JsonCodec.parse("false")).isEqualTo(Boolean.FALSE);
        }

        @Test
        @DisplayName("Parse null")
        void parseNull() {
            assertThat(JsonCodec.parse("null")).isNull();
        }

        @Test
        @DisplayName("Parse nested objects and arrays")
        void parseNested() {
            String json = "{\"users\":[{\"name\":\"Bob\",\"scores\":[10,20]}]}";
            var result = JsonCodec.parse(json);

            assertThat(result).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) result;
            @SuppressWarnings("unchecked")
            var users = (List<Object>) map.get("users");
            assertThat(users).hasSize(1);
            @SuppressWarnings("unchecked")
            var bob = (Map<String, Object>) users.get(0);
            assertThat(bob.get("name")).isEqualTo("Bob");
            @SuppressWarnings("unchecked")
            var scores = (List<Object>) bob.get("scores");
            assertThat(scores).containsExactly(10L, 20L);
        }

        @Test
        @DisplayName("Parse empty object")
        void parseEmptyObject() {
            var result = JsonCodec.parse("{}");
            assertThat(result).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) result;
            assertThat(map).isEmpty();
        }

        @Test
        @DisplayName("Parse empty array")
        void parseEmptyArray() {
            var result = JsonCodec.parse("[]");
            assertThat(result).isInstanceOf(List.class);
            @SuppressWarnings("unchecked")
            var list = (List<Object>) result;
            assertThat(list).isEmpty();
        }

        @Test
        @DisplayName("Parse escape sequences in strings")
        void parseEscapeSequences() {
            var result = JsonCodec.parse("\"line1\\nline2\\ttab\\\\backslash\\\"quote\"");
            assertThat(result).isEqualTo("line1\nline2\ttab\\backslash\"quote");
        }

        @Test
        @DisplayName("Parse forward slash escape")
        void parseForwardSlashEscape() {
            var result = JsonCodec.parse("\"path\\/to\\/file\"");
            assertThat(result).isEqualTo("path/to/file");
        }

        @Test
        @DisplayName("Parse with whitespace between tokens")
        void parseWithWhitespace() {
            String json = "  { \"key\" : \"value\" , \"arr\" : [ 1 , 2 ] }  ";
            var result = JsonCodec.parse(json);
            assertThat(result).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) result;
            assertThat(map).containsEntry("key", "value");
        }
    }

    // =========================================================================
    // Error handling tests
    // =========================================================================

    @Nested
    @DisplayName("Parse errors")
    class ParseErrorTests {

        @Test
        @DisplayName("Null input throws JsonParseException")
        void nullInput_throws() {
            assertThatThrownBy(() -> JsonCodec.parse(null))
                    .isInstanceOf(JsonParseException.class);
        }

        @Test
        @DisplayName("Empty input throws JsonParseException")
        void emptyInput_throws() {
            assertThatThrownBy(() -> JsonCodec.parse(""))
                    .isInstanceOf(JsonParseException.class);
        }

        @Test
        @DisplayName("Malformed JSON throws JsonParseException")
        void malformedJson_throws() {
            assertThatThrownBy(() -> JsonCodec.parse("{invalid}"))
                    .isInstanceOf(JsonParseException.class);
        }

        @Test
        @DisplayName("Unterminated string throws JsonParseException")
        void unterminatedString_throws() {
            assertThatThrownBy(() -> JsonCodec.parse("\"hello"))
                    .isInstanceOf(JsonParseException.class);
        }

        @Test
        @DisplayName("Trailing garbage throws JsonParseException")
        void trailingGarbage_throws() {
            assertThatThrownBy(() -> JsonCodec.parse("42 extra"))
                    .isInstanceOf(JsonParseException.class);
        }
    }

    // =========================================================================
    // Serialize tests
    // =========================================================================

    @Nested
    @DisplayName("Serialize")
    class SerializeTests {

        @Test
        @DisplayName("Serialize null")
        void serializeNull() {
            assertThat(JsonCodec.serialize(null)).isEqualTo("null");
        }

        @Test
        @DisplayName("Serialize string with escapes")
        void serializeStringWithEscapes() {
            String result = JsonCodec.serialize("line1\nline2\ttab\\backslash\"quote");
            assertThat(result).isEqualTo("\"line1\\nline2\\ttab\\\\backslash\\\"quote\"");
        }

        @Test
        @DisplayName("Serialize integer")
        void serializeInteger() {
            assertThat(JsonCodec.serialize(42L)).isEqualTo("42");
        }

        @Test
        @DisplayName("Serialize boolean")
        void serializeBoolean() {
            assertThat(JsonCodec.serialize(true)).isEqualTo("true");
            assertThat(JsonCodec.serialize(false)).isEqualTo("false");
        }
    }

    // =========================================================================
    // Round-trip tests
    // =========================================================================

    @Nested
    @DisplayName("Round-trip")
    class RoundTripTests {

        @Test
        @DisplayName("Parse then serialize produces equivalent JSON")
        void roundTrip_objectPreserved() {
            String original = "{\"name\":\"Alice\",\"age\":30,\"active\":true,\"score\":9.5,\"tags\":[\"a\",\"b\"],\"meta\":null}";
            Object parsed = JsonCodec.parse(original);
            String serialized = JsonCodec.serialize(parsed);
            Object reparsed = JsonCodec.parse(serialized);

            assertThat(reparsed).isEqualTo(parsed);
        }

        @Test
        @DisplayName("Nested structure round-trips correctly")
        void roundTrip_nestedStructure() {
            String original = "{\"outer\":{\"inner\":[1,2,{\"deep\":true}]}}";
            Object parsed = JsonCodec.parse(original);
            String serialized = JsonCodec.serialize(parsed);
            Object reparsed = JsonCodec.parse(serialized);

            assertThat(reparsed).isEqualTo(parsed);
        }
    }
}
