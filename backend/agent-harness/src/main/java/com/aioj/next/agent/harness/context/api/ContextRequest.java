package com.aioj.next.agent.harness.context.api;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** What a contributor gets to build its part of the context; attributes come from the scenario. */
public record ContextRequest(String runId, String scenario, String userMessage, Map<String, Object> attributes,
                             Duration timeBudget) {
    public ContextRequest {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public <T> Optional<T> attribute(String key, Class<T> type) {
        Object value = attributes.get(key);
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }
}
