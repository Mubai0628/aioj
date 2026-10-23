package com.aioj.next.agent.harness.tool.api;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Server-built context for every tool call; the model can neither supply nor
 * change it. {@code serverBindings} are trusted resource ids (for example a
 * workspace id); {@code attributes} carry scenario data that tools and
 * authorizers of that scenario understand and the Harness does not.
 */
public record ToolExecutionContext(
        String runId,
        String userId,
        String conversationId,
        String turnId,
        String policySnapshotId,
        Set<String> grantedScopes,
        Instant serverTime,
        String traceId,
        Map<String, String> serverBindings,
        Map<String, Object> attributes
) {
    public ToolExecutionContext {
        grantedScopes = grantedScopes == null ? Set.of() : Set.copyOf(grantedScopes);
        serverBindings = serverBindings == null ? Map.of() : Map.copyOf(serverBindings);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public String serverBinding(String name) {
        return name == null ? null : serverBindings.get(name);
    }

    public <T> Optional<T> attribute(String key, Class<T> type) {
        Object value = attributes.get(key);
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }
}
