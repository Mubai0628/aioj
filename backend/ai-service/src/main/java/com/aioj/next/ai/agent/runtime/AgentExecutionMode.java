package com.aioj.next.ai.agent.runtime;

/** Safe, user-displayable mode marker; never contains raw model reasoning. */
public enum AgentExecutionMode {
    THINKING,
    STANDARD,
    STANDARD_TOOL_FALLBACK
}
