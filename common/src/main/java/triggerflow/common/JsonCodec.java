package triggerflow.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-rolled recursive descent JSON parser and serializer.
 *
 * <h2>Recursive Descent Parsing</h2>
 * <p>Recursive descent is a top-down parsing technique where each grammar production
 * rule is implemented as a single method. The parser maintains a cursor (position)
 * into the input string and advances it as tokens are consumed. When the parser
 * encounters an opening delimiter (e.g. '{' for objects, '[' for arrays), it
 * recursively calls the appropriate production method, naturally building the
 * parse tree on the call stack.
 *
 * <h2>JSON Grammar (RFC 8259, simplified)</h2>
 * <pre>
 *   value   ::= object | array | string | number | "true" | "false" | "null"
 *   object  ::= '{' (pair (',' pair)*)? '}'
 *   pair    ::= string ':' value
 *   array   ::= '[' (value (',' value)*)? ']'
 *   string  ::= '"' characters '"'
 *   number  ::= '-'? digit+ ('.' digit+)?
 * </pre>
 *
 * <p>Each production is a method in the inner {@link JsonReader} class:
 * {@code readValue()}, {@code readObject()}, {@code readArray()},
 * {@code readString()}, {@code readNumber()}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 *   Object parsed = JsonCodec.parse("{\"name\": \"Alice\", \"age\": 30}");
 *   String json = JsonCodec.serialize(parsed);
 * }</pre>
 *
 * <p>No external dependencies (no Jackson, no Gson). This is a CS fundamentals
 * demonstration of lexing and recursive descent parsing.
 */
public final class JsonCodec {

    private JsonCodec() {
        // utility class
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Parses a JSON string into a Java object graph.
     *
     * <p>Mapping:
     * <ul>
     *   <li>JSON object  -> {@code Map<String, Object>} (LinkedHashMap, preserving order)</li>
     *   <li>JSON array   -> {@code List<Object>} (ArrayList)</li>
     *   <li>JSON string  -> {@code String}</li>
     *   <li>JSON number  -> {@code Long} (if no decimal point) or {@code Double}</li>
     *   <li>JSON true/false -> {@code Boolean}</li>
     *   <li>JSON null    -> {@code null}</li>
     * </ul>
     *
     * @param json the JSON text to parse
     * @return the parsed value
     * @throws JsonParseException if the input is not valid JSON
     */
    public static Object parse(String json) {
        if (json == null || json.isBlank()) {
            throw new JsonParseException("Input is null or empty");
        }
        var reader = new JsonReader(json);
        Object result = reader.readValue();
        reader.skipWhitespace();
        if (reader.hasMore()) {
            throw new JsonParseException("Unexpected trailing content", reader.position());
        }
        return result;
    }

    /**
     * Serializes a Java object graph to a JSON string.
     *
     * <p>Accepts the same types produced by {@link #parse}: Map, List, String,
     * Number, Boolean, and null.
     *
     * @param value the object to serialize
     * @return compact JSON string
     * @throws IllegalArgumentException if the value type is not serializable
     */
    public static String serialize(Object value) {
        var sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    // =========================================================================
    // Serializer
    // =========================================================================

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Number n) {
            writeNumber(sb, n);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(sb, (Map<String, Object>) map);
        } else if (value instanceof List<?> list) {
            writeArray(sb, list);
        } else {
            throw new IllegalArgumentException(
                    "Cannot serialize type: " + value.getClass().getName());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default   -> sb.append(c);
            }
        }
        sb.append('"');
    }

    private static void writeNumber(StringBuilder sb, Number n) {
        if (n instanceof Double d) {
            // Avoid trailing ".0" for whole doubles
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                // Still write as decimal to round-trip correctly
                sb.append(d);
            } else {
                sb.append(d);
            }
        } else if (n instanceof Float f) {
            sb.append(f.doubleValue());
        } else {
            sb.append(n.longValue());
        }
    }

    private static void writeObject(StringBuilder sb, Map<String, Object> map) {
        sb.append('{');
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            writeString(sb, entry.getKey());
            sb.append(':');
            writeValue(sb, entry.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list) {
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            writeValue(sb, list.get(i));
        }
        sb.append(']');
    }

    // =========================================================================
    // Recursive Descent Parser (inner class with position tracking)
    // =========================================================================

    /**
     * Stateful reader that walks through the JSON input character by character.
     *
     * <p>The parser follows the classic recursive descent structure:
     * <ol>
     *   <li>{@link #readValue()} dispatches to the correct production based on
     *       the next non-whitespace character (the "lookahead").</li>
     *   <li>Each production consumes exactly the tokens it owns and returns
     *       the corresponding Java object.</li>
     *   <li>Whitespace is skipped between tokens by {@link #skipWhitespace()}.</li>
     * </ol>
     */
    private static final class JsonReader {

        private final String input;
        private int pos;

        JsonReader(String input) {
            this.input = input;
            this.pos = 0;
        }

        int position() {
            return pos;
        }

        boolean hasMore() {
            return pos < input.length();
        }

        // --- lookahead and cursor movement ---

        /** Returns the current character without advancing. */
        char peek() {
            if (!hasMore()) {
                throw new JsonParseException("Unexpected end of input", pos);
            }
            return input.charAt(pos);
        }

        /** Returns the current character and advances the cursor. */
        char advance() {
            if (!hasMore()) {
                throw new JsonParseException("Unexpected end of input", pos);
            }
            return input.charAt(pos++);
        }

        /** Asserts the current character equals {@code expected} and advances. */
        void expect(char expected) {
            char c = advance();
            if (c != expected) {
                throw new JsonParseException(
                        "Expected '" + expected + "' but got '" + c + "'", pos - 1);
            }
        }

        /** Skips spaces, tabs, carriage returns, and newlines. */
        void skipWhitespace() {
            while (hasMore()) {
                char c = input.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        // --- production: value ---

        /**
         * Entry point for the grammar: dispatches to the correct production
         * based on the lookahead character.
         *
         * <pre>value ::= object | array | string | number | "true" | "false" | "null"</pre>
         */
        Object readValue() {
            skipWhitespace();
            if (!hasMore()) {
                throw new JsonParseException("Unexpected end of input", pos);
            }
            char c = peek();
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield readNumber();
                    }
                    throw new JsonParseException(
                            "Unexpected character '" + c + "'", pos);
                }
            };
        }

        // --- production: object ---

        /**
         * Parses a JSON object.
         * <pre>object ::= '{' (pair (',' pair)*)? '}'</pre>
         * <pre>pair   ::= string ':' value</pre>
         */
        Map<String, Object> readObject() {
            expect('{');
            var map = new LinkedHashMap<String, Object>();
            skipWhitespace();
            if (peek() == '}') {
                advance(); // consume '}'
                return map;
            }
            while (true) {
                skipWhitespace();
                // key must be a string
                if (peek() != '"') {
                    throw new JsonParseException(
                            "Expected string key but got '" + peek() + "'", pos);
                }
                String key = readString();
                skipWhitespace();
                expect(':');
                Object value = readValue();
                map.put(key, value);
                skipWhitespace();
                char next = advance();
                if (next == '}') {
                    return map;
                }
                if (next != ',') {
                    throw new JsonParseException(
                            "Expected ',' or '}' in object but got '" + next + "'", pos - 1);
                }
            }
        }

        // --- production: array ---

        /**
         * Parses a JSON array.
         * <pre>array ::= '[' (value (',' value)*)? ']'</pre>
         */
        List<Object> readArray() {
            expect('[');
            var list = new ArrayList<Object>();
            skipWhitespace();
            if (peek() == ']') {
                advance(); // consume ']'
                return list;
            }
            while (true) {
                Object value = readValue();
                list.add(value);
                skipWhitespace();
                char next = advance();
                if (next == ']') {
                    return list;
                }
                if (next != ',') {
                    throw new JsonParseException(
                            "Expected ',' or ']' in array but got '" + next + "'", pos - 1);
                }
            }
        }

        // --- production: string ---

        /**
         * Parses a JSON string, handling escape sequences.
         * <pre>string ::= '"' characters '"'</pre>
         *
         * <p>Supported escape sequences: {@code \"}, {@code \\}, {@code \/},
         * {@code \b}, {@code \f}, {@code \n}, {@code \r}, {@code \t}.
         */
        String readString() {
            expect('"');
            var sb = new StringBuilder();
            while (hasMore()) {
                char c = advance();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    // escape sequence
                    char escaped = advance();
                    switch (escaped) {
                        case '"'  -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/'  -> sb.append('/');
                        case 'b'  -> sb.append('\b');
                        case 'f'  -> sb.append('\f');
                        case 'n'  -> sb.append('\n');
                        case 'r'  -> sb.append('\r');
                        case 't'  -> sb.append('\t');
                        case 'u'  -> {
                            // Unicode escape: backslash-u XXXX
                            if (pos + 4 > input.length()) {
                                throw new JsonParseException(
                                        "Incomplete unicode escape", pos);
                            }
                            String hex = input.substring(pos, pos + 4);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException e) {
                                throw new JsonParseException(
                                        "Invalid unicode escape: \\u" + hex, pos);
                            }
                            pos += 4;
                        }
                        default -> throw new JsonParseException(
                                "Invalid escape sequence: \\" + escaped, pos - 1);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new JsonParseException("Unterminated string", pos);
        }

        // --- production: number ---

        /**
         * Parses a JSON number (integer or decimal).
         * <pre>number ::= '-'? digit+ ('.' digit+)?</pre>
         *
         * <p>Returns a {@code Long} for integers and a {@code Double} for decimals.
         */
        Number readNumber() {
            int start = pos;
            boolean isDecimal = false;

            if (peek() == '-') {
                advance();
            }
            if (!hasMore() || !isDigit(peek())) {
                throw new JsonParseException("Invalid number", start);
            }
            while (hasMore() && isDigit(peek())) {
                advance();
            }
            // decimal part
            if (hasMore() && peek() == '.') {
                isDecimal = true;
                advance();
                if (!hasMore() || !isDigit(peek())) {
                    throw new JsonParseException("Invalid number: missing digits after '.'", start);
                }
                while (hasMore() && isDigit(peek())) {
                    advance();
                }
            }
            // exponent part (e.g. 1e10, 2.5E-3)
            if (hasMore() && (peek() == 'e' || peek() == 'E')) {
                isDecimal = true;
                advance();
                if (hasMore() && (peek() == '+' || peek() == '-')) {
                    advance();
                }
                if (!hasMore() || !isDigit(peek())) {
                    throw new JsonParseException("Invalid number: missing exponent digits", start);
                }
                while (hasMore() && isDigit(peek())) {
                    advance();
                }
            }

            String numStr = input.substring(start, pos);
            try {
                if (isDecimal) {
                    return Double.parseDouble(numStr);
                } else {
                    return Long.parseLong(numStr);
                }
            } catch (NumberFormatException e) {
                throw new JsonParseException("Invalid number: " + numStr, start);
            }
        }

        // --- production: boolean ---

        Boolean readBoolean() {
            if (peek() == 't') {
                expectLiteral("true");
                return Boolean.TRUE;
            } else {
                expectLiteral("false");
                return Boolean.FALSE;
            }
        }

        // --- production: null ---

        Object readNull() {
            expectLiteral("null");
            return null;
        }

        // --- helpers ---

        private void expectLiteral(String literal) {
            int start = pos;
            for (int i = 0; i < literal.length(); i++) {
                if (!hasMore() || advance() != literal.charAt(i)) {
                    throw new JsonParseException(
                            "Expected '" + literal + "'", start);
                }
            }
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }
    }
}
