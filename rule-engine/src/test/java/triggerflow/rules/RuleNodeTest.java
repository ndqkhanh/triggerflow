package triggerflow.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RuleNode")
class RuleNodeTest {

    @Test
    @DisplayName("maxWeight of ConditionNode equals source weight")
    void conditionNode_maxWeight_equalsSourceWeight() {
        var memory = new RuleNode.ConditionNode("f", ComparisonOp.EQ, "v", DataSource.MEMORY);
        var db = new RuleNode.ConditionNode("f", ComparisonOp.EQ, "v", DataSource.DATABASE);
        var ext = new RuleNode.ConditionNode("f", ComparisonOp.EQ, "v", DataSource.EXTERNAL_SERVICE);

        assertThat(memory.maxWeight()).isEqualTo(1);
        assertThat(db.maxWeight()).isEqualTo(10);
        assertThat(ext.maxWeight()).isEqualTo(100);
    }

    @Test
    @DisplayName("maxWeight of AndNode equals max of children weights")
    void andNode_maxWeight_maxOfChildren() {
        var c1 = new RuleNode.ConditionNode("f", ComparisonOp.EQ, "v", DataSource.MEMORY);
        var c2 = new RuleNode.ConditionNode("f", ComparisonOp.EQ, "v", DataSource.DATABASE);
        var and = new RuleNode.AndNode(List.of(c1, c2));

        assertThat(and.maxWeight()).isEqualTo(10);
    }

    @Test
    @DisplayName("maxWeight of OrNode equals max of children weights")
    void orNode_maxWeight_maxOfChildren() {
        var c1 = new RuleNode.ConditionNode("f", ComparisonOp.EQ, "v", DataSource.MEMORY);
        var c2 = new RuleNode.ConditionNode("f", ComparisonOp.EQ, "v", DataSource.EXTERNAL_SERVICE);
        var or = new RuleNode.OrNode(List.of(c1, c2));

        assertThat(or.maxWeight()).isEqualTo(100);
    }

    @Test
    @DisplayName("maxWeight of NotNode equals child weight")
    void notNode_maxWeight_equalsChildWeight() {
        var cond = new RuleNode.ConditionNode("f", ComparisonOp.EQ, "v", DataSource.DATABASE);
        var not = new RuleNode.NotNode(cond);

        assertThat(not.maxWeight()).isEqualTo(10);
    }

    @Test
    @DisplayName("nested tree: AND(MEMORY, OR(DATABASE, EXTERNAL)) maxWeight is 100")
    void nestedTree_maxWeight_isMaxAcrossAll() {
        var mem = new RuleNode.ConditionNode("f", ComparisonOp.EQ, "v", DataSource.MEMORY);
        var db = new RuleNode.ConditionNode("f", ComparisonOp.EQ, "v", DataSource.DATABASE);
        var ext = new RuleNode.ConditionNode("f", ComparisonOp.EQ, "v", DataSource.EXTERNAL_SERVICE);
        var or = new RuleNode.OrNode(List.of(db, ext));
        var and = new RuleNode.AndNode(List.of(mem, or));

        assertThat(and.maxWeight()).isEqualTo(100);
    }

    @Test
    @DisplayName("empty AndNode maxWeight is 0")
    void emptyAndNode_maxWeight_isZero() {
        var and = new RuleNode.AndNode(List.of());
        assertThat(and.maxWeight()).isEqualTo(0);
    }

    @Test
    @DisplayName("AndNode children list is immutable")
    void andNode_childrenList_isImmutable() {
        var children = new ArrayList<RuleNode>();
        children.add(new RuleNode.ConditionNode("f", ComparisonOp.EQ, "v", DataSource.MEMORY));
        var and = new RuleNode.AndNode(children);

        assertThatThrownBy(() -> and.children().add(
                new RuleNode.ConditionNode("f2", ComparisonOp.EQ, "v2", DataSource.DATABASE)
        )).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("OrNode children list is immutable")
    void orNode_childrenList_isImmutable() {
        var children = new ArrayList<RuleNode>();
        children.add(new RuleNode.ConditionNode("f", ComparisonOp.EQ, "v", DataSource.MEMORY));
        var or = new RuleNode.OrNode(children);

        assertThatThrownBy(() -> or.children().add(
                new RuleNode.ConditionNode("f2", ComparisonOp.EQ, "v2", DataSource.DATABASE)
        )).isInstanceOf(UnsupportedOperationException.class);
    }
}
