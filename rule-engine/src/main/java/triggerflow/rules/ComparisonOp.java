package triggerflow.rules;

/**
 * Comparison operators for rule condition evaluation.
 *
 * <p>Follows the pattern from {@code AstNode.ComparisonOp}: each enum constant
 * provides typed {@code evaluate()} methods for numeric, string, and dynamic
 * (Object) comparisons. Switch expressions ensure exhaustiveness at compile time.
 */
public enum ComparisonOp {

    EQ, NEQ, GT, GTE, LT, LTE, IN, CONTAINS;

    /**
     * Evaluates a numeric comparison between two double values.
     *
     * @throws UnsupportedOperationException for IN and CONTAINS (not applicable to numerics)
     */
    public boolean evaluate(double left, double right) {
        return switch (this) {
            case EQ -> left == right;
            case NEQ -> left != right;
            case GT -> left > right;
            case GTE -> left >= right;
            case LT -> left < right;
            case LTE -> left <= right;
            case IN, CONTAINS -> throw new UnsupportedOperationException(
                    "Cannot apply " + this + " to numeric values");
        };
    }

    /**
     * Evaluates a string comparison.
     *
     * <p>EQ and NEQ are case-insensitive. CONTAINS checks substring presence.
     * IN checks whether {@code left} appears in a comma-separated list {@code right}.
     *
     * @throws UnsupportedOperationException for GT, GTE, LT, LTE (not applicable to strings)
     */
    public boolean evaluate(String left, String right) {
        return switch (this) {
            case EQ -> left.equalsIgnoreCase(right);
            case NEQ -> !left.equalsIgnoreCase(right);
            case CONTAINS -> left.contains(right);
            case IN -> {
                String[] parts = right.split(",");
                for (String part : parts) {
                    if (part.trim().equalsIgnoreCase(left)) {
                        yield true;
                    }
                }
                yield false;
            }
            case GT, GTE, LT, LTE -> throw new UnsupportedOperationException(
                    "Cannot apply " + this + " to strings");
        };
    }

    /**
     * Dynamic dispatch: if both operands are {@link Number}, compares as doubles;
     * otherwise converts both to strings and delegates to the string overload.
     */
    public boolean evaluate(Object left, Object right) {
        if (left instanceof Number leftNum && right instanceof Number rightNum) {
            return evaluate(leftNum.doubleValue(), rightNum.doubleValue());
        }
        String leftStr = left != null ? left.toString() : "";
        String rightStr = right != null ? right.toString() : "";
        return evaluate(leftStr, rightStr);
    }
}
