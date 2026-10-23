package com.aioj.next.agent.harness.tool.api;

import com.aioj.next.agent.harness.tool.internal.DefaultToolRuntime;

import java.util.List;

public final class ToolRuntimes {
    private ToolRuntimes() {
    }

    public static ToolRuntime create(ToolCatalog catalog, List<ToolAuthorizer> authorizers, ToolAuditSink auditSink,
                                     BulkheadLimits limits) {
        return new DefaultToolRuntime(catalog, authorizers, auditSink, limits);
    }
}
