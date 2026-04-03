package triggerflow.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Recursive-descent parser that converts a JSON-derived {@code Map<String, Object>}
 * into a {@link RuleNode} AST.
 *
 * <p>Expected input format (produced by {@code JsonCodec.parse()}):
 * <pre>
 *   { "type": "AND", "children": [ ... ] }
 *   { "type": "OR",  "children": [ ... ] }
 *   { "type": "NOT", "child": { ... } }
 *   { "type": "condition", "field": "payload.country", "op": "EQ", "value": "VN", "source": "MEMORY" }
 * </pre>
 *
 * @see RuleNode
 */
public final class RuleParser {

    private RuleParser() {
        // utility class
    }

    /**
     * Parses a JSON-derived map into a {@link RuleNode} AST.
     *
     * @param json the map representing the rule definition
     * @return the parsed AST root
     * @throws RuleParseException if the map is null, missing required fields, or contains invalid values
     */
    public static RuleNode parse(Map<String, Object> json) {
        if (json == null) {
            throw new RuleParseException("Rule definition must not be null");
        }
        return parseNode(json);
    }

    private static RuleNode parseNode(Map<String, Object> map) {
        Object typeObj = map.get("type");
        if (typeObj == null) {
            throw new RuleParseException("Missing required field: 'type'");
        }
        String type = typeObj.toString().toUpperCase();

        return switch (type) {
            case "AND" -> parseAnd(map);
            case "OR" -> parseOr(map);
            case "NOT" -> parseNot(map);
            case "CONDITION" -> parseCondition(map);
            default -> throw new RuleParseException("Invalid node type: '" + typeObj + "'");
        };
    }

    private static RuleNode.AndNode parseAnd(Map<String, Object> map) {
        List<Map<String, Object>> children = getChildrenList(map);
        List<RuleNode> parsed = new ArrayList<>();
        for (Map<String, Object> child : children) {
            parsed.add(parseNode(child));
        }
        return new RuleNode.AndNode(parsed);
    }

    private static RuleNode.OrNode parseOr(Map<String, Object> map) {
        List<Map<String, Object>> children = getChildrenList(map);
        List<RuleNode> parsed = new ArrayList<>();
        for (Map<String, Object> child : children) {
            parsed.add(parseNode(child));
        }
        return new RuleNode.OrNode(parsed);
    }

    @SuppressWarnings("unchecked")
    private static RuleNode.NotNode parseNot(Map<String, Object> map) {
        // unchecked cast needed: Map<?,?> -> Map<String,Object>
        Object childObj = map.get("child");
        if (childObj == null) {
            throw new RuleParseException("NOT node requires a 'child' field");
        }
        if (!(childObj instanceof Map<?, ?> childMap)) {
            throw new RuleParseException("NOT node 'child' must be a map, got: " + childObj.getClass().getSimpleName());
        }
        return new RuleNode.NotNode(parseNode((Map<String, Object>) childMap));
    }

    private static RuleNode.ConditionNode parseCondition(Map<String, Object> map) {
        String field = requireString(map, "field");
        String opStr = requireString(map, "op");
        Object value = map.get("value");

        ComparisonOp op = parseOp(opStr);
        DataSource source = parseSource(map);

        return new RuleNode.ConditionNode(field, op, value, source);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> getChildrenList(Map<String, Object> map) {
        Object childrenObj = map.get("children");
        if (childrenObj == null) {
            return List.of();
        }
        if (!(childrenObj instanceof List<?> list)) {
            throw new RuleParseException("'children' must be a list, got: " + childrenObj.getClass().getSimpleName());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> itemMap)) {
                throw new RuleParseException("Each child must be a map, got: " + item.getClass().getSimpleName());
            }
            result.add((Map<String, Object>) itemMap);
        }
        return result;
    }

    private static String requireString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) {
            throw new RuleParseException("Missing required field: '" + key + "' in condition node");
        }
        return val.toString();
    }

    private static ComparisonOp parseOp(String opStr) {
        try {
            return ComparisonOp.valueOf(opStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RuleParseException("Invalid comparison operator: '" + opStr + "'", e);
        }
    }

    private static DataSource parseSource(Map<String, Object> map) {
        Object sourceObj = map.get("source");
        if (sourceObj == null) {
            return DataSource.MEMORY; // default
        }
        try {
            return DataSource.valueOf(sourceObj.toString().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RuleParseException("Invalid data source: '" + sourceObj + "'", e);
        }
    }
}
