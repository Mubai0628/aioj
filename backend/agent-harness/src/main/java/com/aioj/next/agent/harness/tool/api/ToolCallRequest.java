package com.aioj.next.agent.harness.tool.api;

/**
 * One tool call the model asked for. {@code toolName} is the internal dotted
 * name; wire encodings belong to the model adapter.
 */
public record ToolCallRequest(String callId, String toolName, String argumentsJson) {
    public ToolCallRequest {
        callId = callId == null ? "" : callId;
    }
}
