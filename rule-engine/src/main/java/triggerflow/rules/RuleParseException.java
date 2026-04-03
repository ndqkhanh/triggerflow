package triggerflow.rules;

/**
 * Thrown when a rule definition map cannot be parsed into a valid {@link RuleNode} AST.
 */
public class RuleParseException extends RuntimeException {

    public RuleParseException(String message) {
        super(message);
    }

    public RuleParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
