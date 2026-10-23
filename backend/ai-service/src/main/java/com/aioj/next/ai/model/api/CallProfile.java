package com.aioj.next.ai.model.api;

/**
 * Call profiles with thinking, token and temperature defaults. Origin: a spike
 * proved that thinking with a small max_tokens yields empty content, so small
 * structured calls force thinking off, and thinking calls get a minimum budget.
 */
public enum CallProfile {
    /** Normal, unrestricted chat turn. */
    CHAT_STREAM(false, 4096, 0.3, false),
    /** Restricted turn: full generation, output-guard check, pseudo-stream replay. */
    CHAT_BUFFERED(false, 4096, 0.3, false),
    /** Problem-draft Agent: model-led tool loop with controlled standard fallback. */
    PROBLEM_DRAFT(false, 8192, 0.2, false),
    /** Small structured outputs (classification/judgement): thinking off, JSON object. */
    STRUCTURED_SMALL(true, 4096, 0.1, true),
    /** Async curator structured outputs (TurnDigest etc.): thinking off, JSON object. */
    CURATOR(true, 4096, 0.1, true),
    /**
     * Thinking-off recovery call: blank-completion retry and budget-exhausted final
     * answers. Thinking can burn the whole max_tokens budget on hard turns and
     * return empty content.
     */
    RECOVERY_ANSWER(true, 4096, 0.3, false),
    /**
     * One-off completions outside the Agent loop (analysis, import parsing, memory
     * merge, connectivity tests): thinking and output format as configured; the
     * caller usually sets temperature and token limit on the request.
     */
    COMPLETION(false, 4096, 0.3, false);

    /** Minimum max_tokens while thinking stays on: small budgets are eaten by reasoning. */
    public static final int MIN_MAX_TOKENS_WHEN_THINKING = 2_000;

    private final boolean forceThinkingDisabled;
    private final int defaultMaxTokens;
    private final double defaultTemperature;
    private final boolean forceJsonOutput;

    CallProfile(boolean forceThinkingDisabled, int defaultMaxTokens, double defaultTemperature, boolean forceJsonOutput) {
        this.forceThinkingDisabled = forceThinkingDisabled;
        this.defaultMaxTokens = defaultMaxTokens;
        this.defaultTemperature = defaultTemperature;
        this.forceJsonOutput = forceJsonOutput;
    }

    public boolean forceThinkingDisabled() {
        return forceThinkingDisabled;
    }

    public int defaultMaxTokens() {
        return defaultMaxTokens;
    }

    public double defaultTemperature() {
        return defaultTemperature;
    }

    /** Structured profiles always ask for a JSON object, whatever the endpoint's JSON switch says. */
    public boolean forceJsonOutput() {
        return forceJsonOutput;
    }
}
