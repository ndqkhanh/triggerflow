package triggerflow.common;

/**
 * Thrown by {@link JsonCodec} when the input is not valid JSON.
 *
 * <p>The message includes the position in the input where the error was detected,
 * making it straightforward to diagnose malformed payloads.
 */
public class JsonParseException extends RuntimeException {

    public JsonParseException(String message) {
        super(message);
    }

    public JsonParseException(String message, int position) {
        super(message + " at position " + position);
    }
}
