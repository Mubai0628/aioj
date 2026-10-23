package com.aioj.next.agent.harness.tool.api;

import java.util.List;

/** A tool the catalog left out at registration, and why. */
public record ToolRejection(String toolName, String toolClass, List<String> reasons) {
    public ToolRejection {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
