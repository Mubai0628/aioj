package com.aioj.next.ai.model.api;

import tools.jackson.databind.JsonNode;

/**
 * A tool offered to the model (plan A1.2): the model package's own type, so it
 * does not depend on the Harness tool types.
 *
 * @param name       internal dotted name, such as {@code problem.read}
 * @param parameters the input JSON Schema (MFJS subset), passed to the provider unchanged
 * @param strict     per-function strict schema flag for providers that have one;
 *                   {@code null} means the provider's default
 */
public record ToolSpec(String name, String description, JsonNode parameters, Boolean strict) {

    public ToolSpec(String name, String description, JsonNode parameters) {
        this(name, description, parameters, null);
    }
}
