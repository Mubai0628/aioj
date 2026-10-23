package com.aioj.next.ai.agent;

import com.aioj.next.ai.model.api.ModelResponse;
import com.aioj.next.ai.model.api.ModelUsage;
import com.aioj.next.ai.model.api.ProviderId;
import com.aioj.next.ai.model.api.ToolCall;

import java.util.List;

/**
 * Model answers for agent tests, in the argument order of the old gateway
 * response. A call with no tokens counts as one without reported usage.
 */
public final class TestModelResponses {

    private TestModelResponses() {
    }

    public static ModelResponse response(String content, List<ToolCall> toolCalls, String finishReason,
                                         long promptTokens, long completionTokens, long cacheHitTokens,
                                         String provider, String model) {
        return response(content, null, toolCalls, finishReason, promptTokens, completionTokens, cacheHitTokens,
                provider, model);
    }

    public static ModelResponse response(String content, String reasoningContent, List<ToolCall> toolCalls,
                                         String finishReason, long promptTokens, long completionTokens,
                                         long cacheHitTokens, String provider, String model) {
        return new ModelResponse(content, reasoningContent, toolCalls, finishReason,
                new ModelUsage(promptTokens, completionTokens, cacheHitTokens,
                        promptTokens > 0 || completionTokens > 0),
                provider == null ? null : ProviderId.fromConfig(provider), model);
    }
}
