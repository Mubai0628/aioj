package com.aioj.next.agent.harness.runtime.api;

/**
 * Why a run did not complete. {@code detail} is safe to log;
 * {@code modelErrorKind} is set for {@link Kind#MODEL_ERROR}.
 */
public record RunFailure(Kind kind, String detail, ModelCallException.Kind modelErrorKind) {
    public enum Kind {
        MODEL_ERROR,
        EMPTY_RESPONSE,
        /** A {@code FAIL_CLOSED} tool failed, so no answer may be produced. */
        TOOL_FAILED_CLOSED,
        /** An interceptor threw, or rejected the final answer twice. */
        INTERCEPTOR_ERROR,
        CANCELLED,
        DEADLINE_EXCEEDED,
        INTERNAL_ERROR
    }
}
