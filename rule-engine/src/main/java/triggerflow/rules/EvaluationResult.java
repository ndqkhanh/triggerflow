package triggerflow.rules;

import java.time.Duration;

/**
 * Result of evaluating a rule tree against an {@link EventContext}.
 *
 * @param matched             whether the rule matched the event
 * @param conditionsEvaluated number of leaf conditions that were actually evaluated
 * @param conditionsSkipped   number of leaf conditions skipped due to short-circuit
 * @param elapsed             wall-clock time spent on evaluation
 */
public record EvaluationResult(
        boolean matched,
        int conditionsEvaluated,
        int conditionsSkipped,
        Duration elapsed
) {}
