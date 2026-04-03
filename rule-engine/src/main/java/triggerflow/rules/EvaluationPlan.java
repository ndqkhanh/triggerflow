package triggerflow.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pre-compiled evaluation plan that reorders rule tree children by
 * {@link DataSource#weight()} ascending, so cheaper conditions are evaluated
 * first and expensive ones can be short-circuited.
 *
 * <p>The optimization is purely structural: it produces a new AST with
 * the same logical semantics but with AND/OR children sorted cheapest-first.
 */
public final class EvaluationPlan {

    private final RuleNode optimizedRoot;

    private EvaluationPlan(RuleNode optimizedRoot) {
        this.optimizedRoot = optimizedRoot;
    }

    /**
     * Compiles an optimized evaluation plan from the given rule tree.
     *
     * @param root the original rule AST
     * @return a plan with children sorted by weight ascending
     */
    public static EvaluationPlan compile(RuleNode root) {
        return new EvaluationPlan(optimize(root));
    }

    /**
     * Returns the weight-sorted rule tree.
     */
    public RuleNode optimizedRoot() {
        return optimizedRoot;
    }

    private static RuleNode optimize(RuleNode node) {
        return switch (node) {
            case RuleNode.ConditionNode c -> c;
            case RuleNode.NotNode n -> new RuleNode.NotNode(optimize(n.child()));
            case RuleNode.AndNode a -> {
                List<RuleNode> sorted = sortByWeight(a.children());
                yield new RuleNode.AndNode(sorted);
            }
            case RuleNode.OrNode o -> {
                List<RuleNode> sorted = sortByWeight(o.children());
                yield new RuleNode.OrNode(sorted);
            }
        };
    }

    private static List<RuleNode> sortByWeight(List<RuleNode> children) {
        List<RuleNode> optimized = new ArrayList<>();
        for (RuleNode child : children) {
            optimized.add(optimize(child));
        }
        optimized.sort(Comparator.comparingInt(RuleNode::maxWeight));
        return optimized;
    }
}
