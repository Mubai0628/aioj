package com.aioj.next.agent.harness.runtime.api;

import com.aioj.next.agent.harness.runtime.internal.DefaultAgentLoop;
import com.aioj.next.agent.harness.tool.api.ToolRuntime;

public final class AgentLoops {
    private AgentLoops() {
    }

    public static AgentLoop create(ModelPort modelPort, ToolRuntime toolRuntime) {
        return new DefaultAgentLoop(modelPort, toolRuntime);
    }
}
