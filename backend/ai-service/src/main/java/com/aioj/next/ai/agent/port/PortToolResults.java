package com.aioj.next.ai.agent.port;

import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolStatus;

/** How a tool reports a port that is down: SERVICE_UNAVAILABLE, so the model answers without it. */
public final class PortToolResults {
    public static final String MEMORY = "memory";
    public static final String PROFILE = "profile";

    private PortToolResults() {
    }

    public static ToolResult<Object> unavailable(String errorCode, PortUnavailableException ex) {
        return ToolResult.failure(null, ToolStatus.SERVICE_UNAVAILABLE, errorCode,
                ex.port() + " is unavailable right now (" + ex.reason() + "); answer without it");
    }
}
