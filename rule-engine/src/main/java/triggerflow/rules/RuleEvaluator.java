package triggerflow.rules;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Tree-walking interpreter that evaluates a {@link RuleNode} AST against an
 * {@link EventContext}, with weighted short-circuit optimization.
 *
 * <p>Follows the exact pattern from {@code MatchingEngine.evaluateCondition()}:
 * a switch expression on the sealed {@link RuleNode} type dispatches to
 * type-specific evaluation methods.
 *
 * <h3>Short-Circuit Strategy</h3>
 * <ul>
 *   <li><b>AND</b>: children sorted by {@link RuleNode#maxWeight()} ascending
 *       (cheapest first). First {@code false} short-circuits; remaining children skipped.</li>
 *   <li><b>OR</b>: children sorted by weight ascending.
 *       First {@code true} short-circuits; remaining children skipped.</li>
 * </ul>
 */
public class RuleEvaluator {

    /**
     * Evaluates the given rule tree against the provided event context.
     *
     * @param rule the rule AST to evaluate
     * @param ctx  the event context providing field values
     * @return an {@link EvaluationResult} with match outcome and statistics
     */
    public EvaluationResult evaluate(RuleNode rule, EventContext ctx) {
        long start = System.nanoTime();
        var stats = new EvalStats();
        boolean matched = evaluateNode(rule, ctx, stats);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        return new EvaluationResult(matched, stats.evaluated, stats.skipped, elapsed);
    }

    private boolean evaluateNode(RuleNode node, EventContext ctx, EvalStats stats) {
        return switch (node) {
            case RuleNode.AndNode and -> evaluateAnd(and, ctx, stats);
            case RuleNode.OrNode or -> evaluateOr(or, ctx, stats);
            case RuleNode.NotNode not -> !evaluateNode(not.child(), ctx, stats);
            case RuleNode.ConditionNode cond -> evaluateCondition(cond, ctx, stats);
        };
    }

    private boolean evaluateAnd(RuleNode.AndNode and, EventContext ctx, EvalStats stats) {
        List<RuleNode> sorted = sortByWeight(and.children());
        for (int i = 0; i < sorted.size(); i++) {
            if (!evaluateNode(sorted.get(i), ctx, stats)) {
                // Short-circuit: skip remaining children
                stats.skipped += countConditions(sorted.subList(i + 1, sorted.size()));
                return false;
            }
        }
        return true;
    }

    private boolean evaluateOr(RuleNode.OrNode or, EventContext ctx, EvalStats stats) {
        List<RuleNode> sorted = sortByWeight(or.children());
        for (int i = 0; i < sorted.size(); i++) {
            if (evaluateNode(sorted.get(i), ctx, stats)) {
                // Short-circuit: skip remaining children
                stats.skipped += countConditions(sorted.subList(i + 1, sorted.size()));
                return true;
            }
        }
        return false;
    }

    private boolean evaluateCondition(RuleNode.ConditionNode cond, EventContext ctx, EvalStats stats) {
        stats.evaluated++;
        Object resolved = ctx.resolveField(cond.field());
        if (resolved == null) {
            return false;
        }
        return cond.op().evaluate(resolved, cond.value());
    }

    private static List<RuleNode> sortByWeight(List<RuleNode> children) {
        List<RuleNode> sorted = new ArrayList<>(children);
        sorted.sort(Comparator.comparingInt(RuleNode::maxWeight));
        return sorted;
    }

    private static int countConditions(List<RuleNode> nodes) {
        int count = 0;
        for (RuleNode node : nodes) {
            count += countConditionsIn(node);
        }
        return count;
    }

    private static int countConditionsIn(RuleNode node) {
        return switch (node) {
            case RuleNode.ConditionNode ignored -> 1;
            case RuleNode.AndNode a -> countConditions(a.children());
            case RuleNode.OrNode o -> countConditions(o.children());
            case RuleNode.NotNode n -> countConditionsIn(n.child());
        };
    }

    /**
     * Mutable statistics accumulator used during a single evaluation pass.
     */
    static final class EvalStats {
        int evaluated;
        int skipped;
    }
}
