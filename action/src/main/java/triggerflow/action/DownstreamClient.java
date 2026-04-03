package triggerflow.action;

/**
 * Abstraction over a downstream service that can execute actions.
 *
 * <p>Real implementations call external HTTP services; the
 * {@link InMemoryDownstreamClient} provides a test double.
 */
public interface DownstreamClient {

    /**
     * Executes the given action request against the downstream service.
     *
     * @param request the action to execute
     * @return the result of execution
     */
    ActionResult execute(ActionRequest request);
}
