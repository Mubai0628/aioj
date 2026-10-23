package com.aioj.next.agent.harness.runtime.api;

/**
 * What {@link LoopInterceptor#beforeFinal} decides about an answer: keep it,
 * have the model try once more with an instruction, or replace it with a
 * server-written text.
 */
public record FinalDecision(Type type, String text) {
    public enum Type {
        ACCEPT,
        RETRY,
        REPLACE
    }

    public FinalDecision {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (type != Type.ACCEPT && (text == null || text.isBlank())) {
            throw new IllegalArgumentException(type + " needs a text");
        }
    }

    public static FinalDecision accept() {
        return new FinalDecision(Type.ACCEPT, null);
    }

    /** One more model call with {@code instruction} appended as a system message. */
    public static FinalDecision retry(String instruction) {
        return new FinalDecision(Type.RETRY, instruction);
    }

    public static FinalDecision replace(String content) {
        return new FinalDecision(Type.REPLACE, content);
    }
}
