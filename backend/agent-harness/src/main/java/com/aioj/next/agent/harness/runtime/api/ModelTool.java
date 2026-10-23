package com.aioj.next.agent.harness.runtime.api;

import tools.jackson.databind.JsonNode;

/** A tool as the model sees it; {@code name} is the internal dotted name. */
public record ModelTool(String name, String description, JsonNode inputSchema) {
}
