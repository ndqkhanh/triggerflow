package triggerflow.common;

/**
 * Known event types in the TriggerFlow system.
 *
 * <p>Each variant corresponds to a business event produced by upstream services
 * (ride-hailing, payments, loyalty, etc.). The rule engine matches incoming
 * events against these types to determine which actions to trigger.
 */
public enum EventType {

    RIDE_COMPLETED,
    PAYMENT_SUCCEEDED,
    ORDER_PLACED,
    USER_REGISTERED,
    REWARD_CLAIMED,
    REFERRAL_COMPLETED,
    SUBSCRIPTION_RENEWED,
    PROFILE_UPDATED
}
