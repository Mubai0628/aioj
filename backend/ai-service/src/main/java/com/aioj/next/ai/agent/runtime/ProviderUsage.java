package com.aioj.next.ai.agent.runtime;

import com.aioj.next.ai.model.api.ModelResponse;
import com.aioj.next.ai.model.api.ModelUsage;

/** Provider-reported token usage of one actual model invocation, with the provider and model it came from. */
public record ProviderUsage(
        String provider,
        String model,
        long promptTokens,
        long completionTokens,
        boolean reported
) {
    /** For tests and callers that know the usage. */
    public ProviderUsage(String provider, String model, long promptTokens, long completionTokens) {
        this(provider, model, promptTokens, completionTokens, true);
    }

    public static ProviderUsage from(ModelResponse response) {
        if (response == null) {
            return null;
        }
        ModelUsage usage = response.usage();
        return new ProviderUsage(response.provider() == null ? null : response.provider().configName(),
                response.model(), usage.promptTokens(), usage.completionTokens(), usage.reported());
    }
}
