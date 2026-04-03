package triggerflow.action;

import java.time.Duration;

/**
 * Immutable result of executing an {@link ActionRequest} against a downstream service.
 *
 * @param actionId   the identifier of the action that was executed
 * @param success    whether the execution succeeded
 * @param response   human-readable response or error message
 * @param latency    wall-clock time spent executing the action
 * @param retryCount number of retry attempts before this result was produced
 */
public record ActionResult(
        String actionId,
        boolean success,
        String response,
        Duration latency,
        int retryCount
) {}
