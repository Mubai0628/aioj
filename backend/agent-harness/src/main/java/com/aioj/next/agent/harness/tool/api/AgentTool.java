package com.aioj.next.agent.harness.tool.api;

import tools.jackson.databind.JsonNode;

/**
 * A tool the model may call. It receives a server-built context and input that
 * already passed its schema; it never sees the model or raw HTTP.
 */
public interface AgentTool {
    ToolDescriptor descriptor();

    ToolResult execute(ToolExecutionContext context, JsonNode input);
}
