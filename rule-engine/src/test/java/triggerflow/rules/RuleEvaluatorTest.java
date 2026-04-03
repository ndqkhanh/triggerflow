package triggerflow.rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import triggerflow.common.TriggerFlowEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RuleEvaluator")
class RuleEvaluatorTest {

    private RuleEvaluator evaluator;
    private EventContext ctx;

    @BeforeEach
    void setUp() {
        evaluator = new RuleEvaluator();

        var event = new TriggerFlowEvent(
                "evt-001",
                "RIDE_COMPLETED",
                "user-42",
                Map.of(
                        "country", "VN",
                        "amount", 150.0,
                        "rideCount", 10
                ),
                Instant.parse("2025-01-15T10:00:00Z"),
                "ride-service"
        );
        ctx = new EventContext(event, Map.of("tier", "gold", "fraud_score", 0.05));
    }

    // --- Helpers ---

    private static RuleNode.ConditionNode memoryCondition(String field, ComparisonOp op, Object value) {
        return new RuleNode.ConditionNode(field, op, value, DataSource.MEMORY);
    }

    private static RuleNode.ConditionNode dbCondition(String field, ComparisonOp op, Object value) {
        return new RuleNode.ConditionNode(field, op, value, DataSource.DATABASE);
    }

    private static RuleNode.ConditionNode extCondition(String field, ComparisonOp op, Object value) {
        return new RuleNode.ConditionNode(field, op, value, DataSource.EXTERNAL_SERVICE);
    }

    @Nested
    @DisplayName("simple condition")
    class SimpleConditionTests {

        @Test
        @DisplayName("matching condition returns matched=true")
        void matchingCondition_returnsTrue() {
            var cond = memoryCondition("country", ComparisonOp.EQ, "VN");

            var result = evaluator.evaluate(cond, ctx);

            assertThat(result.matched()).isTrue();
            assertThat(result.conditionsEvaluated()).isEqualTo(1);
            assertThat(result.conditionsSkipped()).isEqualTo(0);
        }

        @Test
        @DisplayName("non-matching condition returns matched=false")
        void nonMatchingCondition_returnsFalse() {
            var cond = memoryCondition("country", ComparisonOp.EQ, "US");

            var result = evaluator.evaluate(cond, ctx);

            assertThat(result.matched()).isFalse();
            assertThat(result.conditionsEvaluated()).isEqualTo(1);
        }

        @Test
        @DisplayName("condition with null resolved field returns false without crashing")
        void nullField_returnsFalse() {
            var cond = memoryCondition("nonexistent", ComparisonOp.EQ, "anything");

            var result = evaluator.evaluate(cond, ctx);

            assertThat(result.matched()).isFalse();
            assertThat(result.conditionsEvaluated()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("AND logic")
    class AndTests {

        @Test
        @DisplayName("all true children returns true")
        void allTrue_returnsTrue() {
            var and = new RuleNode.AndNode(List.of(
                    memoryCondition("country", ComparisonOp.EQ, "VN"),
                    memoryCondition("payload.amount", ComparisonOp.GT, 100.0)
            ));

            var result = evaluator.evaluate(and, ctx);

            assertThat(result.matched()).isTrue();
            assertThat(result.conditionsEvaluated()).isEqualTo(2);
            assertThat(result.conditionsSkipped()).isEqualTo(0);
        }

        @Test
        @DisplayName("first child false short-circuits remaining")
        void firstFalse_shortCircuits() {
            // First condition fails (MEMORY, weight=1) -> second (also MEMORY) should be skipped
            var and = new RuleNode.AndNode(List.of(
                    memoryCondition("country", ComparisonOp.EQ, "US"),
                    memoryCondition("payload.amount", ComparisonOp.GT, 100.0)
            ));

            var result = evaluator.evaluate(and, ctx);

            assertThat(result.matched()).isFalse();
            assertThat(result.conditionsEvaluated()).isEqualTo(1);
            assertThat(result.conditionsSkipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("last child false means all evaluated, none skipped")
        void lastFalse_allEvaluated() {
            var and = new RuleNode.AndNode(List.of(
                    memoryCondition("country", ComparisonOp.EQ, "VN"),
                    memoryCondition("payload.amount", ComparisonOp.GT, 999.0)
            ));

            var result = evaluator.evaluate(and, ctx);

            assertThat(result.matched()).isFalse();
            assertThat(result.conditionsEvaluated()).isEqualTo(2);
            assertThat(result.conditionsSkipped()).isEqualTo(0);
        }

        @Test
        @DisplayName("empty AND returns true (vacuous truth)")
        void emptyAnd_returnsTrue() {
            var and = new RuleNode.AndNode(List.of());

            var result = evaluator.evaluate(and, ctx);

            assertThat(result.matched()).isTrue();
        }

        @Test
        @DisplayName("single-child AND behaves like the child")
        void singleChild_behavesLikeChild() {
            var and = new RuleNode.AndNode(List.of(
                    memoryCondition("country", ComparisonOp.EQ, "VN")
            ));

            var result = evaluator.evaluate(and, ctx);

            assertThat(result.matched()).isTrue();
            assertThat(result.conditionsEvaluated()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("OR logic")
    class OrTests {

        @Test
        @DisplayName("first child true short-circuits")
        void firstTrue_shortCircuits() {
            var or = new RuleNode.OrNode(List.of(
                    memoryCondition("country", ComparisonOp.EQ, "VN"),
                    memoryCondition("payload.amount", ComparisonOp.GT, 999.0)
            ));

            var result = evaluator.evaluate(or, ctx);

            assertThat(result.matched()).isTrue();
            assertThat(result.conditionsEvaluated()).isEqualTo(1);
            assertThat(result.conditionsSkipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("all false returns false")
        void allFalse_returnsFalse() {
            var or = new RuleNode.OrNode(List.of(
                    memoryCondition("country", ComparisonOp.EQ, "US"),
                    memoryCondition("payload.amount", ComparisonOp.GT, 999.0)
            ));

            var result = evaluator.evaluate(or, ctx);

            assertThat(result.matched()).isFalse();
            assertThat(result.conditionsEvaluated()).isEqualTo(2);
            assertThat(result.conditionsSkipped()).isEqualTo(0);
        }

        @Test
        @DisplayName("empty OR returns false")
        void emptyOr_returnsFalse() {
            var or = new RuleNode.OrNode(List.of());

            var result = evaluator.evaluate(or, ctx);

            assertThat(result.matched()).isFalse();
        }

        @Test
        @DisplayName("single-child OR behaves like the child")
        void singleChild_behavesLikeChild() {
            var or = new RuleNode.OrNode(List.of(
                    memoryCondition("country", ComparisonOp.EQ, "VN")
            ));

            var result = evaluator.evaluate(or, ctx);

            assertThat(result.matched()).isTrue();
            assertThat(result.conditionsEvaluated()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("NOT logic")
    class NotTests {

        @Test
        @DisplayName("NOT inverts true to false")
        void notTrue_returnsFalse() {
            var not = new RuleNode.NotNode(
                    memoryCondition("country", ComparisonOp.EQ, "VN")
            );

            var result = evaluator.evaluate(not, ctx);

            assertThat(result.matched()).isFalse();
        }

        @Test
        @DisplayName("NOT inverts false to true")
        void notFalse_returnsTrue() {
            var not = new RuleNode.NotNode(
                    memoryCondition("country", ComparisonOp.EQ, "US")
            );

            var result = evaluator.evaluate(not, ctx);

            assertThat(result.matched()).isTrue();
        }
    }

    @Nested
    @DisplayName("weighted short-circuit")
    class WeightedShortCircuitTests {

        @Test
        @DisplayName("AND: MEMORY(fail) evaluated before EXTERNAL(match), EXTERNAL skipped")
        void weightedAnd_cheapFailSkipsExpensive() {
            // EXTERNAL_SERVICE condition would match, MEMORY condition fails
            // Evaluator should sort: MEMORY first (weight=1), EXTERNAL second (weight=100)
            // MEMORY fails -> EXTERNAL skipped
            var and = new RuleNode.AndNode(List.of(
                    extCondition("country", ComparisonOp.EQ, "VN"),  // weight=100, would match
                    memoryCondition("country", ComparisonOp.EQ, "US")  // weight=1, fails
            ));

            var result = evaluator.evaluate(and, ctx);

            assertThat(result.matched()).isFalse();
            assertThat(result.conditionsEvaluated()).isEqualTo(1);
            assertThat(result.conditionsSkipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("OR: MEMORY(match) evaluated before EXTERNAL(match), EXTERNAL skipped")
        void weightedOr_cheapMatchSkipsExpensive() {
            // Both would match, but MEMORY is evaluated first due to lower weight
            var or = new RuleNode.OrNode(List.of(
                    extCondition("country", ComparisonOp.EQ, "VN"),  // weight=100, would match
                    memoryCondition("country", ComparisonOp.EQ, "VN")  // weight=1, matches
            ));

            var result = evaluator.evaluate(or, ctx);

            assertThat(result.matched()).isTrue();
            assertThat(result.conditionsEvaluated()).isEqualTo(1);
            assertThat(result.conditionsSkipped()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("complex nested evaluation")
    class ComplexTests {

        @Test
        @DisplayName("AND(OR(MEMORY_match, DATABASE_fail), EXTERNAL_match) evaluates correctly")
        void complexNested_evaluatesCorrectly() {
            var or = new RuleNode.OrNode(List.of(
                    memoryCondition("country", ComparisonOp.EQ, "VN"),      // matches
                    dbCondition("payload.amount", ComparisonOp.GT, 999.0)    // fails
            ));
            var ext = extCondition("enriched.tier", ComparisonOp.EQ, "gold"); // matches
            var and = new RuleNode.AndNode(List.of(or, ext));

            var result = evaluator.evaluate(and, ctx);

            assertThat(result.matched()).isTrue();
            // OR: MEMORY match (1 evaluated) -> DB skipped (1 skipped)
            // EXTERNAL: 1 evaluated
            // Total: 2 evaluated, 1 skipped
            assertThat(result.conditionsEvaluated()).isEqualTo(2);
            assertThat(result.conditionsSkipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("EvaluationResult.elapsed is non-negative")
        void evaluationResult_elapsedIsNonNegative() {
            var cond = memoryCondition("country", ComparisonOp.EQ, "VN");

            var result = evaluator.evaluate(cond, ctx);

            assertThat(result.elapsed()).isNotNull();
            assertThat(result.elapsed().isNegative()).isFalse();
        }
    }
}
