package com.aioj.next.agent.harness.tool.api;

import com.aioj.next.agent.harness.tool.internal.DefaultToolRuntime;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.function.UnaryOperator;

public final class ToolRuntimes {
    private ToolRuntimes() {
    }

    public static ToolRuntime create(ToolCatalog catalog, List<ToolAuthorizer> authorizers, ToolAuditSink auditSink,
                                     BulkheadLimits limits) {
        return new DefaultToolRuntime(catalog, authorizers, auditSink, limits);
    }

    /**
     * @param objectMapper the assembling service's mapper, so the model sees tool data
     *                     serialized the way that service always has
     */
    public static ToolRuntime create(ToolCatalog catalog, List<ToolAuthorizer> authorizers, ToolAuditSink auditSink,
                                     BulkheadLimits limits, ObjectMapper objectMapper) {
        return new DefaultToolRuntime(catalog, authorizers, auditSink, limits, objectMapper);
    }

    /**
     * @param taskDecorator wraps each tool execution before it goes to its pool, so the tool
     *                      thread can carry the turn's logging context (trace id)
     */
    public static ToolRuntime create(ToolCatalog catalog, List<ToolAuthorizer> authorizers, ToolAuditSink auditSink,
                                     BulkheadLimits limits, ObjectMapper objectMapper,
                                     UnaryOperator<Runnable> taskDecorator) {
        return new DefaultToolRuntime(catalog, authorizers, auditSink, limits, objectMapper, taskDecorator);
    }
}
