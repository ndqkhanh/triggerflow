package triggerflow.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EvaluationPlan")
class EvaluationPlanTest {

    @Test
    @DisplayName("AND children reordered by weight ascending")
    void andChildren_reorderedByWeightAsc() {
        var ext = new RuleNode.ConditionNode("f1", ComparisonOp.EQ, "v", DataSource.EXTERNAL_SERVICE);
        var mem = new RuleNode.ConditionNode("f2", ComparisonOp.EQ, "v", DataSource.MEMORY);
        var db = new RuleNode.ConditionNode("f3", ComparisonOp.EQ, "v", DataSource.DATABASE);
        var and = new RuleNode.AndNode(List.of(ext, mem, db));

        var plan = EvaluationPlan.compile(and);
        var optimized = (RuleNode.AndNode) plan.optimizedRoot();

        assertThat(optimized.children()).hasSize(3);
        assertThat(((RuleNode.ConditionNode) optimized.children().get(0)).source()).isEqualTo(DataSource.MEMORY);
        assertThat(((RuleNode.ConditionNode) optimized.children().get(1)).source()).isEqualTo(DataSource.DATABASE);
        assertThat(((RuleNode.ConditionNode) optimized.children().get(2)).source()).isEqualTo(DataSource.EXTERNAL_SERVICE);
    }

    @Test
    @DisplayName("OR children reordered by weight ascending")
    void orChildren_reorderedByWeightAsc() {
        var ext = new RuleNode.ConditionNode("f1", ComparisonOp.EQ, "v", DataSource.EXTERNAL_SERVICE);
        var mem = new RuleNode.ConditionNode("f2", ComparisonOp.EQ, "v", DataSource.MEMORY);
        var or = new RuleNode.OrNode(List.of(ext, mem));

        var plan = EvaluationPlan.compile(or);
        var optimized = (RuleNode.OrNode) plan.optimizedRoot();

        assertThat(optimized.children()).hasSize(2);
        assertThat(((RuleNode.ConditionNode) optimized.children().get(0)).source()).isEqualTo(DataSource.MEMORY);
        assertThat(((RuleNode.ConditionNode) optimized.children().get(1)).source()).isEqualTo(DataSource.EXTERNAL_SERVICE);
    }

    @Test
    @DisplayName("NOT child preserved unchanged")
    void notChild_preservedUnchanged() {
        var cond = new RuleNode.ConditionNode("f", ComparisonOp.EQ, "v", DataSource.DATABASE);
        var not = new RuleNode.NotNode(cond);

        var plan = EvaluationPlan.compile(not);
        var optimized = (RuleNode.NotNode) plan.optimizedRoot();

        assertThat(optimized.child()).isInstanceOf(RuleNode.ConditionNode.class);
        assertThat(((RuleNode.ConditionNode) optimized.child()).source()).isEqualTo(DataSource.DATABASE);
    }

    @Test
    @DisplayName("single condition unchanged")
    void singleCondition_unchanged() {
        var cond = new RuleNode.ConditionNode("f", ComparisonOp.EQ, "v", DataSource.EXTERNAL_SERVICE);

        var plan = EvaluationPlan.compile(cond);

        assertThat(plan.optimizedRoot()).isEqualTo(cond);
    }

    @Test
    @DisplayName("nested tree optimized recursively")
    void nestedTree_optimizedRecursively() {
        // AND(OR(EXTERNAL, MEMORY), DATABASE)
        // After optimization:
        //   OR children should be reordered: MEMORY, EXTERNAL
        //   AND children should be reordered by maxWeight: DATABASE(10), OR(100)
        var ext = new RuleNode.ConditionNode("f1", ComparisonOp.EQ, "v", DataSource.EXTERNAL_SERVICE);
        var mem = new RuleNode.ConditionNode("f2", ComparisonOp.EQ, "v", DataSource.MEMORY);
        var db = new RuleNode.ConditionNode("f3", ComparisonOp.EQ, "v", DataSource.DATABASE);
        var or = new RuleNode.OrNode(List.of(ext, mem));
        var and = new RuleNode.AndNode(List.of(or, db));

        var plan = EvaluationPlan.compile(and);
        var optimizedAnd = (RuleNode.AndNode) plan.optimizedRoot();

        // AND children: DATABASE (weight=10) before OR (weight=100)
        assertThat(optimizedAnd.children().get(0)).isInstanceOf(RuleNode.ConditionNode.class);
        assertThat(((RuleNode.ConditionNode) optimizedAnd.children().get(0)).source()).isEqualTo(DataSource.DATABASE);

        // Second child is the OR, whose children should also be sorted
        var optimizedOr = (RuleNode.OrNode) optimizedAnd.children().get(1);
        assertThat(((RuleNode.ConditionNode) optimizedOr.children().get(0)).source()).isEqualTo(DataSource.MEMORY);
        assertThat(((RuleNode.ConditionNode) optimizedOr.children().get(1)).source()).isEqualTo(DataSource.EXTERNAL_SERVICE);
    }
}
