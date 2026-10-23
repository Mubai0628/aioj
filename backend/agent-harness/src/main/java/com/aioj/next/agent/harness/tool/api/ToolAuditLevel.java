package com.aioj.next.agent.harness.tool.api;

public enum ToolAuditLevel {
    /** Arguments (redacted by the sink), status, hashes and latency. */
    FULL,
    /** Everything except the arguments. */
    NO_ARGUMENTS
}
