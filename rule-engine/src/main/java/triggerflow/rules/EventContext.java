package triggerflow.rules;

import triggerflow.common.TriggerFlowEvent;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Evaluation context that wraps a {@link TriggerFlowEvent} together with
 * any enriched data gathered from external sources.
 *
 * <p>Provides dotted-path field resolution for the rule evaluator:
 * <ul>
 *   <li>{@code "payload.country"} — resolves from the event payload</li>
 *   <li>{@code "event.userId"} — resolves top-level event fields</li>
 *   <li>{@code "enriched.tier"} or {@code "external.fraud_score"} — resolves from enriched data</li>
 *   <li>{@code "country"} (single segment) — checks payload first, then enriched data</li>
 * </ul>
 *
 * <p>The enriched data map is defensively copied on construction to guarantee immutability.
 */
public record EventContext(TriggerFlowEvent event, Map<String, Object> enrichedData) {

    public EventContext {
        enrichedData = enrichedData != null
                ? Collections.unmodifiableMap(new HashMap<>(enrichedData))
                : Map.of();
    }

    /**
     * Resolves a dotted field path like "payload.country" or "event.userId".
     *
     * @param field the dotted path to resolve
     * @return the resolved value, or {@code null} if not found
     */
    public Object resolveField(String field) {
        int dot = field.indexOf('.');
        if (dot < 0) {
            // Single-segment: check event top-level, then payload, then enriched
            return event().payload().getOrDefault(field, enrichedData().get(field));
        }
        String prefix = field.substring(0, dot);
        String rest = field.substring(dot + 1);
        return switch (prefix) {
            case "payload" -> resolveFromMap(event().payload(), rest);
            case "event" -> resolveEventField(rest);
            case "enriched", "external" -> resolveFromMap(enrichedData(), rest);
            default -> null;
        };
    }

    private Object resolveEventField(String field) {
        return switch (field) {
            case "eventType" -> event().eventType();
            case "userId" -> event().userId();
            case "sourceService" -> event().sourceService();
            case "eventId" -> event().eventId();
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private Object resolveFromMap(Map<String, Object> map, String path) {
        int dot = path.indexOf('.');
        if (dot < 0) {
            return map.get(path);
        }
        String key = path.substring(0, dot);
        Object val = map.get(key);
        if (val instanceof Map<?, ?> nested) {
            return resolveFromMap((Map<String, Object>) nested, path.substring(dot + 1));
        }
        return null;
    }
}
