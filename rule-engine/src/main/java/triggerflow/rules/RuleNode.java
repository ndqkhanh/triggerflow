package triggerflow.rules;

import java.util.List;

/**
 * Sealed interface AST hierarchy for rule definitions.
 *
 * <p>Mirrors the sealed-interface + record pattern from {@code grabflow.ride.dsl.AstNode}.
 * Each node type is a record that implements this sealed interface, enabling
 * exhaustive pattern matching in switch expressions (Java 21 preview).
 *
 * <h3>AST Structure</h3>
 * <pre>
 *   RuleNode
 *   ├── AndNode(children)    — all children must match
 *   ├── OrNode(children)     — at least one child must match
 *   ├── NotNode(child)       — inverts child result
 *   └── ConditionNode(field, op, value, source)
 * </pre>
 */
public sealed interface RuleNode {

    /**
     * Logical AND: all children must evaluate to true.
     * Children list is defensively copied to guarantee immutability.
     */
    record AndNode(List<RuleNode> children) implements RuleNode {
        public AndNode {
            children = List.copyOf(children);
        }
    }

    /**
     * Logical OR: at least one child must evaluate to true.
     * Children list is defensively copied to guarantee immutability.
     */
    record OrNode(List<RuleNode> children) implements RuleNode {
        public OrNode {
            children = List.copyOf(children);
        }
    }

    /**
     * Logical NOT: inverts the result of the child node.
     */
    record NotNode(RuleNode child) implements RuleNode {}

    /**
     * Leaf condition: compares a resolved field value against an expected value.
     *
     * @param field  dotted field path (e.g. "payload.country", "event.userId")
     * @param op     comparison operator
     * @param value  expected value to compare against
     * @param source data source classification for weight-based ordering
     */
    record ConditionNode(String field, ComparisonOp op, Object value, DataSource source) implements RuleNode {}

    /**
     * Returns the maximum {@link DataSource#weight()} across all condition nodes
     * in this subtree. Used by the evaluator to sort children cheapest-first.
     */
    default int maxWeight() {
        return switch (this) {
            case ConditionNode c -> c.source().weight();
            case AndNode a -> a.children().stream().mapToInt(RuleNode::maxWeight).max().orElse(0);
            case OrNode o -> o.children().stream().mapToInt(RuleNode::maxWeight).max().orElse(0);
            case NotNode n -> n.child().maxWeight();
        };
    }
}
