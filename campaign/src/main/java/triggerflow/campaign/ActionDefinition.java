package triggerflow.campaign;

import java.util.Map;

/**
 * Immutable definition of an action to execute when a campaign triggers.
 *
 * @param actionType    the action type identifier (e.g. "send_push", "apply_discount")
 * @param targetService the downstream service to invoke
 * @param params        key-value parameters for the action; defensively copied
 */
public record ActionDefinition(String actionType, String targetService, Map<String, String> params) {

    public ActionDefinition {
        params = params != null ? Map.copyOf(params) : Map.of();
    }
}
