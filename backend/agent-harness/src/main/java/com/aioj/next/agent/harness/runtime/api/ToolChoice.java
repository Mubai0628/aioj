package com.aioj.next.agent.harness.runtime.api;

public enum ToolChoice {
    /** The model decides. */
    AUTO,
    /** The model must call a tool; sent only to ports that support it natively. */
    REQUIRED
}
