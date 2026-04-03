package triggerflow.action;

/**
 * Supported downstream action types.
 *
 * <p>Each variant maps to a specific side-effect the system can trigger in
 * response to an evaluated rule. The action dispatcher uses this enum to
 * route requests to the correct downstream client.
 */
public enum ActionType {
    SEND_MESSAGE,
    GRANT_REWARD,
    APPLY_COMPENSATION,
    SEND_PUSH_NOTIFICATION,
    CALL_WEBHOOK
}
