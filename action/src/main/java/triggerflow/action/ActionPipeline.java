package triggerflow.action;

import java.time.Duration;

/**
 * Multi-stage pipeline for processing action requests.
 *
 * <h2>Stages</h2>
 * <ol>
 *   <li><b>Validate</b> — reject malformed requests</li>
 *   <li><b>Rate-limit</b> — check per-service rate limiter</li>
 *   <li><b>Delay-or-dispatch</b> — schedule if delayed, dispatch if immediate</li>
 *   <li><b>Result</b> — return the action result</li>
 * </ol>
 *
 * <p>Delayed actions are submitted to the {@link DelayScheduler}; when their timer
 * fires the scheduler invokes the {@link ActionDispatcher} on their behalf.
 */
public class ActionPipeline {

    private final ActionDispatcher dispatcher;
    private final ServiceRateLimiter rateLimiter;
    private final DelayScheduler delayScheduler;

    /**
     * @param dispatcher     dispatches actions to downstream services
     * @param rateLimiter    per-service rate limiter
     * @param delayScheduler schedules delayed action execution
     */
    public ActionPipeline(ActionDispatcher dispatcher, ServiceRateLimiter rateLimiter, DelayScheduler delayScheduler) {
        this.dispatcher = dispatcher;
        this.rateLimiter = rateLimiter;
        this.delayScheduler = delayScheduler;
    }

    /**
     * Processes a single action request through all pipeline stages.
     *
     * @param request the action request to process
     * @return the result of processing
     */
    public ActionResult process(ActionRequest request) {
        // Stage 1: Validate
        ActionResult validationError = validate(request);
        if (validationError != null) {
            return validationError;
        }

        // Stage 2: Rate-limit check
        if (!rateLimiter.tryAcquire(request.targetService())) {
            return new ActionResult(request.actionId(), false, "RATE_LIMITED", Duration.ZERO, 0);
        }

        // Stage 3: Delay-or-dispatch
        if (request.delay() != null && !request.delay().isZero()) {
            return scheduleDelayed(request);
        }

        // Stage 4: Immediate dispatch
        return dispatchImmediate(request);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private ActionResult validate(ActionRequest request) {
        if (request.type() == null) {
            return new ActionResult(request.actionId(), false, "INVALID: missing action type", Duration.ZERO, 0);
        }
        if (request.targetService() == null || request.targetService().isBlank()) {
            return new ActionResult(request.actionId(), false, "INVALID: missing target service", Duration.ZERO, 0);
        }
        return null;
    }

    private ActionResult scheduleDelayed(ActionRequest request) {
        delayScheduler.schedule(request, () -> dispatcher.dispatch(request));
        return new ActionResult(request.actionId(), true, "SCHEDULED", Duration.ZERO, 0);
    }

    private ActionResult dispatchImmediate(ActionRequest request) {
        try {
            return dispatcher.dispatch(request).join();
        } catch (Exception e) {
            return new ActionResult(request.actionId(), false, "DISPATCH_ERROR: " + e.getMessage(), Duration.ZERO, 0);
        }
    }
}
