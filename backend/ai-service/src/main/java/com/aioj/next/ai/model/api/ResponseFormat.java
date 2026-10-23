package com.aioj.next.ai.model.api;

import tools.jackson.databind.JsonNode;

/**
 * Requested output format. A JSON-schema request goes out as {@code json_schema}
 * only where {@link ProviderCapabilities#jsonSchema()} says so; elsewhere it is sent
 * as {@code json_object}, and the schema has to be in the prompt as well.
 */
public record ResponseFormat(Type type, String schemaName, JsonNode schema) {

    public enum Type {
        TEXT,
        JSON_OBJECT,
        JSON_SCHEMA
    }

    private static final ResponseFormat TEXT = new ResponseFormat(Type.TEXT, null, null);
    private static final ResponseFormat JSON_OBJECT = new ResponseFormat(Type.JSON_OBJECT, null, null);

    public static ResponseFormat text() {
        return TEXT;
    }

    public static ResponseFormat jsonObject() {
        return JSON_OBJECT;
    }

    public static ResponseFormat jsonSchema(String name, JsonNode schema) {
        if (name == null || name.isBlank() || schema == null) {
            throw new IllegalArgumentException("a JSON schema format needs a name and a schema");
        }
        return new ResponseFormat(Type.JSON_SCHEMA, name, schema);
    }

    public boolean isJson() {
        return type != Type.TEXT;
    }
}
