package com.aioj.next.agent.harness.tool.internal;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Validator for the MFJS-safe JSON-Schema subset tool descriptors may use
 * (type, properties, required, items, enum, additionalProperties, length,
 * item and numeric bounds); schemas outside the subset are rejected at
 * registration. Moved unchanged from ai-service's ToolInputSchemaValidator.
 */
public final class SchemaValidator {
    private static final List<String> UNSUPPORTED_KEYS = List.of(
            "oneOf", "anyOf", "allOf", "not", "$ref", "patternProperties", "dependencies",
            "dependentRequired", "dependentSchemas", "if", "then", "else", "contains"
    );

    private SchemaValidator() {
    }

    public static List<String> validate(JsonNode schema, JsonNode instance) {
        List<String> errors = new ArrayList<>();
        if (schema == null || schema.isNull()) {
            return errors;
        }
        validateNode(schema, instance, "$", errors);
        return errors;
    }

    /** Whether the schema only uses keywords this validator, and MFJS strict mode, understand. */
    public static boolean isSupportedSubset(JsonNode schema) {
        if (schema == null || schema.isNull()) {
            return false;
        }
        List<String> unsupported = new ArrayList<>();
        collectUnsupported(schema, "$", unsupported);
        return unsupported.isEmpty();
    }

    private static void collectUnsupported(JsonNode schema, String path, List<String> unsupported) {
        if (!schema.isObject()) {
            return;
        }
        for (String key : UNSUPPORTED_KEYS) {
            if (schema.has(key)) {
                unsupported.add(path + ": unsupported keyword " + key);
            }
        }
        JsonNode properties = schema.path("properties");
        if (properties.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = properties.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                collectUnsupported(entry.getValue(), path + "." + entry.getKey(), unsupported);
            }
        }
        JsonNode items = schema.path("items");
        if (items.isObject()) {
            collectUnsupported(items, path + "[]", unsupported);
        }
    }

    private static void validateNode(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (value == null || value.isNull()) {
            if (schema.has("type")) {
                errors.add(path + ": must not be null");
            }
            return;
        }
        if (schema.has("enum")) {
            boolean matches = false;
            for (JsonNode candidate : schema.get("enum")) {
                if (candidate.equals(value)) {
                    matches = true;
                    break;
                }
            }
            if (!matches) {
                errors.add(path + ": value is not one of the allowed enum values");
                return;
            }
        }
        String type = schema.path("type").isString() ? schema.get("type").asString("") : null;
        if (type == null) {
            return;
        }
        switch (type) {
            case "object" -> validateObject(schema, value, path, errors);
            case "array" -> validateArray(schema, value, path, errors);
            case "string" -> {
                if (!value.isString()) {
                    errors.add(path + ": expected string");
                } else {
                    if (schema.has("minLength") && value.asString("").length() < schema.get("minLength").asInt(0)) {
                        errors.add(path + ": shorter than minLength " + schema.get("minLength").asInt(0));
                    }
                    if (schema.has("maxLength") && value.asString("").length() > schema.get("maxLength").asInt(0)) {
                        errors.add(path + ": longer than maxLength " + schema.get("maxLength").asInt(0));
                    }
                }
            }
            case "integer" -> {
                if (!value.isIntegralNumber()) {
                    errors.add(path + ": expected integer");
                } else {
                    validateNumericBounds(schema, value, path, errors);
                }
            }
            case "number" -> {
                if (!value.isNumber()) {
                    errors.add(path + ": expected number");
                } else {
                    validateNumericBounds(schema, value, path, errors);
                }
            }
            case "boolean" -> {
                if (!value.isBoolean()) {
                    errors.add(path + ": expected boolean");
                }
            }
            default -> {
                // Unknown types are rejected at registration; be permissive here.
            }
        }
    }

    private static void validateObject(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (!value.isObject()) {
            errors.add(path + ": expected object");
            return;
        }
        JsonNode required = schema.path("required");
        if (required.isArray()) {
            for (JsonNode field : required) {
                if (field.isString() && !value.has(field.asString(""))) {
                    errors.add(path + "." + field.asString("") + ": is required");
                }
            }
        }
        JsonNode properties = schema.path("properties");
        boolean additionalAllowed = !schema.has("additionalProperties")
                || schema.get("additionalProperties").asBoolean(true);
        Iterator<Map.Entry<String, JsonNode>> fields = value.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode childSchema = properties.isObject() ? properties.get(entry.getKey()) : null;
            if (childSchema != null) {
                validateNode(childSchema, entry.getValue(), path + "." + entry.getKey(), errors);
            } else if (!additionalAllowed) {
                errors.add(path + "." + entry.getKey() + ": is not allowed");
            }
        }
    }

    private static void validateArray(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (!value.isArray()) {
            errors.add(path + ": expected array");
            return;
        }
        if (schema.has("minItems") && value.size() < schema.get("minItems").asInt(0)) {
            errors.add(path + ": fewer than minItems " + schema.get("minItems").asInt(0));
        }
        if (schema.has("maxItems") && value.size() > schema.get("maxItems").asInt(0)) {
            errors.add(path + ": more than maxItems " + schema.get("maxItems").asInt(0));
        }
        JsonNode items = schema.path("items");
        if (!items.isObject()) {
            return;
        }
        for (int i = 0; i < value.size(); i++) {
            validateNode(items, value.get(i), path + "[" + i + "]", errors);
        }
    }

    private static void validateNumericBounds(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (schema.has("minimum") && value.decimalValue().compareTo(schema.get("minimum").decimalValue()) < 0) {
            errors.add(path + ": less than minimum " + schema.get("minimum").asString(""));
        }
        if (schema.has("maximum") && value.decimalValue().compareTo(schema.get("maximum").decimalValue()) > 0) {
            errors.add(path + ": greater than maximum " + schema.get("maximum").asString(""));
        }
    }
}
