package com.aioj.next.ai.agent.runtime;

import com.aioj.next.ai.model.api.ModelResponse;
import com.aioj.next.ai.model.api.ModelUsage;

/** Per-run token accumulator; usage is settled once per turn by the facade. */
public final class UsageMeter {
    private long promptTokens;
    private long completionTokens;
    private long cacheHitTokens;

    public void add(ModelResponse response) {
        if (response == null) {
            return;
        }
        ModelUsage usage = response.usage();
        promptTokens += usage.promptTokens();
        completionTokens += usage.completionTokens();
        cacheHitTokens += usage.cacheHitTokens();
    }

    public long promptTokens() {
        return promptTokens;
    }

    public long completionTokens() {
        return completionTokens;
    }

    public long cacheHitTokens() {
        return cacheHitTokens;
    }
}
