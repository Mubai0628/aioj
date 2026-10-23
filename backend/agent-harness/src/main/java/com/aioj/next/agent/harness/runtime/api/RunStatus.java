package com.aioj.next.agent.harness.runtime.api;

public enum RunStatus {
    /** An answer was produced; budget exhaustion still ends here, with a warning. */
    COMPLETED,
    CANCELLED,
    DEADLINE_EXCEEDED,
    FAILED
}
