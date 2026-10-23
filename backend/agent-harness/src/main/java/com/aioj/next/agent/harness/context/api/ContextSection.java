package com.aioj.next.agent.harness.context.api;

/**
 * One section of the model context. {@code type} is the scenario's own label
 * (the Harness does not interpret it); {@code atomic} sections are kept whole
 * or dropped whole when the budget is tight.
 */
public record ContextSection(String type, TrustLevel trust, int tokenEstimate, boolean atomic, String content) {
    public ContextSection {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("section type must not be blank");
        }
        trust = trust == null ? TrustLevel.EXTERNAL_UNTRUSTED : trust;
        tokenEstimate = Math.max(0, tokenEstimate);
        content = content == null ? "" : content;
    }
}
