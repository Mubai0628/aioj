package com.aioj.next.agent.harness.tool.api;

import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Set;

/**
 * What the pipeline and the model know about a tool. The fields beyond the
 * model-facing ones (plan §3.3): {@code budgetCategory} replaces matching
 * tool names in the loop budget, {@code executionClass} picks the bulkhead,
 * {@code failurePolicy} says whether an infrastructure failure ends the run.
 * Which scenarios offer a tool is decided when the scenario is assembled, not
 * here. {@link ToolCatalog} validates descriptors at registration.
 */
public record ToolDescriptor(
        String name,
        String version,
        String description,
        JsonNode inputSchema,
        Set<String> requiredScopes,
        int maxResultTokens,
        Duration timeout,
        String budgetCategory,
        ExecutionClass executionClass,
        FailurePolicy failurePolicy,
        ToolAuditLevel auditLevel
) {
    public ToolDescriptor {
        requiredScopes = requiredScopes == null ? Set.of() : Set.copyOf(requiredScopes);
    }
}
