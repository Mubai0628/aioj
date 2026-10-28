package com.aioj.next.agent.harness.context.api;

import java.util.List;

/**
 * One section of the model context (A3.2). {@code type} and {@code dataClass} are the
 * scenario's own labels (the Harness does not interpret them); {@code priority} orders
 * sections when the scenario renders them; {@code atomic} sections are kept whole or
 * dropped whole when the budget is tight. A conversational section carries its
 * {@code turns} instead of (or besides) plain {@code content}.
 */
public record ContextSection(String type, TrustLevel trust, int priority, int tokenEstimate, boolean atomic,
                             String content, List<ContextTurn> turns, List<ContextSource> sources,
                             String dataClass) {
    public ContextSection {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("section type must not be blank");
        }
        trust = trust == null ? TrustLevel.EXTERNAL_UNTRUSTED : trust;
        tokenEstimate = Math.max(0, tokenEstimate);
        content = content == null ? "" : content;
        turns = turns == null ? List.of() : List.copyOf(turns);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** A plain text section without priority, turns or sources. */
    public ContextSection(String type, TrustLevel trust, int tokenEstimate, boolean atomic, String content) {
        this(type, trust, 0, tokenEstimate, atomic, content, List.of(), List.of(), null);
    }
}
