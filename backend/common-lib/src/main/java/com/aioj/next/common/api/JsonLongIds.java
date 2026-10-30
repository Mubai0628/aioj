package com.aioj.next.common.api;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan S6: an integer of 16 or more digits in audit JSON goes to the client as a string,
 * wherever it sits. Browsers read JSON numbers as doubles, which hold 15 to 17 significant
 * digits, so a 19-digit snowflake id comes back with its last digits wrong. The rule does
 * not look at key names: ids also sit in arrays and under keys that do not end in "Id".
 * Stored JSON keeps its numbers; only what is handed out is converted.
 */
public final class JsonLongIds {
    /** 10^15, the smallest 16-digit integer. */
    private static final long SIXTEEN_DIGITS = 1_000_000_000_000_000L;
    private static final BigInteger SIXTEEN_DIGITS_BIG = BigInteger.valueOf(SIXTEEN_DIGITS);

    private JsonLongIds() {
    }

    /** The id as text, or null. */
    public static String text(Long id) {
        return id == null ? null : String.valueOf(id);
    }

    /** The tree with every integer of 16 or more digits replaced by its decimal string; the argument is not changed. */
    public static JsonNode stringifyNode(JsonNode node) {
        if (node == null || !containsLong(node)) {
            return node;
        }
        return replace(node.deepCopy());
    }

    /** The same for a value read into maps and lists, such as a JSON column parsed into {@code Map<String, Object>}. */
    public static Object stringifyValue(Object value) {
        if (value instanceof JsonNode node) {
            return stringifyNode(node);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(key, stringifyValue(item)));
            return copy;
        }
        if (value instanceof Collection<?> items) {
            List<Object> copy = new ArrayList<>(items.size());
            items.forEach(item -> copy.add(stringifyValue(item)));
            return copy;
        }
        if (value instanceof Long number && isLong(number)) {
            return String.valueOf(number);
        }
        if (value instanceof BigInteger number && number.abs().compareTo(SIXTEEN_DIGITS_BIG) >= 0) {
            return number.toString();
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> stringifyMap(Map<String, Object> map) {
        return map == null ? null : (Map<String, Object>) stringifyValue(map);
    }

    /** JSON kept as text, such as an audit summary column: parsed, converted and written back. Text that is not JSON is returned as it is. */
    public static String stringifyText(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            JsonNode tree = mapper.readTree(json);
            return containsLong(tree) ? mapper.writeValueAsString(replace(tree)) : json;
        } catch (JacksonException | IllegalArgumentException ex) {
            return json;
        }
    }

    private static JsonNode replace(JsonNode node) {
        if (isLong(node)) {
            return JsonNodeFactory.instance.stringNode(node.bigIntegerValue().toString());
        }
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> property : new ArrayList<>(object.properties())) {
                object.set(property.getKey(), replace(property.getValue()));
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                array.set(i, replace(array.get(i)));
            }
        }
        return node;
    }

    private static boolean containsLong(JsonNode node) {
        if (isLong(node)) {
            return true;
        }
        if (node.isContainer()) {
            for (JsonNode child : node) {
                if (containsLong(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isLong(JsonNode node) {
        return node.isIntegralNumber() && node.bigIntegerValue().abs().compareTo(SIXTEEN_DIGITS_BIG) >= 0;
    }

    private static boolean isLong(long number) {
        return number >= SIXTEEN_DIGITS || number <= -SIXTEEN_DIGITS;
    }
}
