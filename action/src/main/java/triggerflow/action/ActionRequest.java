package triggerflow.action;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Immutable request describing an action to be dispatched to a downstream service.
 *
 * <p>An {@code ActionRequest} is the unit of work flowing through the action pipeline.
 * It captures what to do ({@link #type}), where to send it ({@link #targetService}),
 * how to parameterise it ({@link #params}), and when to execute it ({@link #delay}).
 *
 * <p>Compact constructor defaults:
 * <ul>
 *   <li>{@code actionId} — random UUID if null</li>
 *   <li>{@code createdAt} — {@code Instant.now()} if null</li>
 *   <li>{@code params} — empty unmodifiable map if null</li>
 * </ul>
 *
 * @param actionId      unique identifier for this action instance
 * @param type          the kind of downstream action to execute
 * @param targetService the downstream service name (used for rate-limiting)
 * @param params        key-value parameters for the action
 * @param delay         optional delay before execution (may be null for immediate)
 * @param campaignId    the campaign that triggered this action
 * @param userId        the user this action targets
 * @param createdAt     when the request was created
 */
public record ActionRequest(
        String actionId,
        ActionType type,
        String targetService,
        Map<String, String> params,
        Duration delay,
        String campaignId,
        String userId,
        Instant createdAt
) {

    public ActionRequest {
        if (actionId == null) actionId = java.util.UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
        params = params != null ? Map.copyOf(params) : Map.of();
    }
}
