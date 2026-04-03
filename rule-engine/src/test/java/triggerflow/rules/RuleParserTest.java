package triggerflow.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RuleParser")
class RuleParserTest {

    @Test
    @DisplayName("parse simple condition produces ConditionNode with correct fields")
    void parseSimpleCondition_producesConditionNode() {
        Map<String, Object> json = Map.of(
                "type", "condition",
                "field", "payload.country",
                "op", "EQ",
                "value", "VN",
                "source", "MEMORY"
        );

        RuleNode result = RuleParser.parse(json);

        assertThat(result).isInstanceOf(RuleNode.ConditionNode.class);
        var cond = (RuleNode.ConditionNode) result;
        assertThat(cond.field()).isEqualTo("payload.country");
        assertThat(cond.op()).isEqualTo(ComparisonOp.EQ);
        assertThat(cond.value()).isEqualTo("VN");
        assertThat(cond.source()).isEqualTo(DataSource.MEMORY);
    }

    @Test
    @DisplayName("parse AND with two conditions produces AndNode with 2 children")
    void parseAnd_twoConditions_producesAndNode() {
        Map<String, Object> json = Map.of(
                "type", "AND",
                "children", List.of(
                        Map.of("type", "condition", "field", "f1", "op", "EQ", "value", "v1"),
                        Map.of("type", "condition", "field", "f2", "op", "NEQ", "value", "v2")
                )
        );

        RuleNode result = RuleParser.parse(json);

        assertThat(result).isInstanceOf(RuleNode.AndNode.class);
        var and = (RuleNode.AndNode) result;
        assertThat(and.children()).hasSize(2);
        assertThat(and.children().get(0)).isInstanceOf(RuleNode.ConditionNode.class);
        assertThat(and.children().get(1)).isInstanceOf(RuleNode.ConditionNode.class);
    }

    @Test
    @DisplayName("parse OR with two conditions produces OrNode with 2 children")
    void parseOr_twoConditions_producesOrNode() {
        Map<String, Object> json = Map.of(
                "type", "OR",
                "children", List.of(
                        Map.of("type", "condition", "field", "f1", "op", "EQ", "value", "v1"),
                        Map.of("type", "condition", "field", "f2", "op", "GT", "value", 10)
                )
        );

        RuleNode result = RuleParser.parse(json);

        assertThat(result).isInstanceOf(RuleNode.OrNode.class);
        var or = (RuleNode.OrNode) result;
        assertThat(or.children()).hasSize(2);
    }

    @Test
    @DisplayName("parse NOT wrapping condition produces NotNode")
    void parseNot_wrapsCondition_producesNotNode() {
        Map<String, Object> json = Map.of(
                "type", "NOT",
                "child", Map.of("type", "condition", "field", "f1", "op", "EQ", "value", "v1")
        );

        RuleNode result = RuleParser.parse(json);

        assertThat(result).isInstanceOf(RuleNode.NotNode.class);
        var not = (RuleNode.NotNode) result;
        assertThat(not.child()).isInstanceOf(RuleNode.ConditionNode.class);
    }

    @Test
    @DisplayName("parse deeply nested AND(OR(cond, cond), cond)")
    void parseDeeplyNested_createsCorrectTree() {
        Map<String, Object> json = Map.of(
                "type", "AND",
                "children", List.of(
                        Map.of("type", "OR", "children", List.of(
                                Map.of("type", "condition", "field", "f1", "op", "EQ", "value", "v1"),
                                Map.of("type", "condition", "field", "f2", "op", "EQ", "value", "v2")
                        )),
                        Map.of("type", "condition", "field", "f3", "op", "GT", "value", 5)
                )
        );

        RuleNode result = RuleParser.parse(json);

        assertThat(result).isInstanceOf(RuleNode.AndNode.class);
        var and = (RuleNode.AndNode) result;
        assertThat(and.children()).hasSize(2);
        assertThat(and.children().get(0)).isInstanceOf(RuleNode.OrNode.class);
        assertThat(and.children().get(1)).isInstanceOf(RuleNode.ConditionNode.class);
    }

    @Test
    @DisplayName("invalid type throws RuleParseException")
    void invalidType_throwsRuleParseException() {
        Map<String, Object> json = Map.of("type", "XOR");

        assertThatThrownBy(() -> RuleParser.parse(json))
                .isInstanceOf(RuleParseException.class)
                .hasMessageContaining("Invalid node type");
    }

    @Test
    @DisplayName("missing 'field' in condition throws RuleParseException")
    void missingField_throwsRuleParseException() {
        Map<String, Object> json = Map.of(
                "type", "condition",
                "op", "EQ",
                "value", "v"
        );

        assertThatThrownBy(() -> RuleParser.parse(json))
                .isInstanceOf(RuleParseException.class)
                .hasMessageContaining("field");
    }

    @Test
    @DisplayName("missing 'op' in condition throws RuleParseException")
    void missingOp_throwsRuleParseException() {
        Map<String, Object> json = Map.of(
                "type", "condition",
                "field", "f1",
                "value", "v"
        );

        assertThatThrownBy(() -> RuleParser.parse(json))
                .isInstanceOf(RuleParseException.class)
                .hasMessageContaining("op");
    }

    @Test
    @DisplayName("invalid op value throws RuleParseException")
    void invalidOp_throwsRuleParseException() {
        Map<String, Object> json = Map.of(
                "type", "condition",
                "field", "f1",
                "op", "LIKE",
                "value", "v"
        );

        assertThatThrownBy(() -> RuleParser.parse(json))
                .isInstanceOf(RuleParseException.class)
                .hasMessageContaining("Invalid comparison operator");
    }

    @Test
    @DisplayName("invalid source value throws RuleParseException")
    void invalidSource_throwsRuleParseException() {
        Map<String, Object> json = Map.of(
                "type", "condition",
                "field", "f1",
                "op", "EQ",
                "value", "v",
                "source", "CLOUD"
        );

        assertThatThrownBy(() -> RuleParser.parse(json))
                .isInstanceOf(RuleParseException.class)
                .hasMessageContaining("Invalid data source");
    }

    @Test
    @DisplayName("empty children list creates empty AND node")
    void emptyChildren_createsEmptyAndNode() {
        Map<String, Object> json = Map.of(
                "type", "AND",
                "children", List.of()
        );

        RuleNode result = RuleParser.parse(json);

        assertThat(result).isInstanceOf(RuleNode.AndNode.class);
        assertThat(((RuleNode.AndNode) result).children()).isEmpty();
    }

    @Test
    @DisplayName("null input throws RuleParseException")
    void nullInput_throwsRuleParseException() {
        assertThatThrownBy(() -> RuleParser.parse(null))
                .isInstanceOf(RuleParseException.class)
                .hasMessageContaining("null");
    }

    @Test
    @DisplayName("missing type throws RuleParseException")
    void missingType_throwsRuleParseException() {
        Map<String, Object> json = new HashMap<>();
        json.put("field", "f1");

        assertThatThrownBy(() -> RuleParser.parse(json))
                .isInstanceOf(RuleParseException.class)
                .hasMessageContaining("type");
    }
}
