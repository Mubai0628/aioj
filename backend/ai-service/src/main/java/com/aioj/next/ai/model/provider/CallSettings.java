package com.aioj.next.ai.model.provider;

import com.aioj.next.ai.model.api.CallProfile;
import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ModelRequest;
import com.aioj.next.ai.model.api.ProviderCapabilities;
import com.aioj.next.ai.model.api.ProviderId;
import com.aioj.next.ai.model.api.ResponseFormat;

import java.util.Locale;

/**
 * Per-call settings after the call profile's safety defaults are applied to the
 * endpoint configuration.
 *
 * @param reasoningEffort {@code low}, {@code high} or {@code max}, the levels DeepSeek takes
 */
public record CallSettings(boolean thinking, String reasoningEffort, double temperature, int maxTokens,
                           ResponseFormat responseFormat) {

    /**
     * Reasoning counts toward max_tokens. At effort max DeepSeek reasoned through all
     * 8,192 tokens of a hard turn and answered nothing (T1.3 eval, 2026-10-23); it
     * documents up to 384K output tokens, so thinking at max effort gets this much room.
     * Other providers keep the general floor until their limits are checked.
     */
    static final int DEEPSEEK_MAX_EFFORT_MIN_TOKENS = 16_384;

    public static CallSettings resolve(ModelEndpointConfig endpoint, ModelRequest request,
                                       ProviderCapabilities capabilities) {
        CallProfile profile = request.profile();
        boolean thinking = capabilities.thinks(endpoint, profile, request.thinkingDisabled());
        String effort = effort(endpoint.reasoningEffort());
        int maxTokens = request.maxTokens() != null && request.maxTokens() > 0
                ? request.maxTokens()
                : endpoint.maxTokens() == null || endpoint.maxTokens() <= 0
                        ? profile.defaultMaxTokens()
                        : endpoint.maxTokens();
        if (thinking) {
            maxTokens = Math.max(maxTokens, minimumWhenThinking(capabilities.provider(), effort));
        }
        double temperature = request.temperature() != null
                ? request.temperature()
                : endpoint.temperature() == null ? profile.defaultTemperature() : endpoint.temperature();
        ResponseFormat format = request.responseFormat();
        if (!format.isJson() && profile.forceJsonOutput()) {
            format = ResponseFormat.jsonObject();
        }
        return new CallSettings(thinking, effort, temperature, maxTokens, format);
    }

    /** A configured effort DeepSeek takes; anything else is its default, {@code high}. */
    static String effort(String configured) {
        String effort = configured == null ? "" : configured.trim().toLowerCase(Locale.ROOT);
        return "low".equals(effort) || "max".equals(effort) ? effort : "high";
    }

    private static int minimumWhenThinking(ProviderId provider, String effort) {
        return provider == ProviderId.DEEPSEEK && "max".equals(effort)
                ? DEEPSEEK_MAX_EFFORT_MIN_TOKENS : CallProfile.MIN_MAX_TOKENS_WHEN_THINKING;
    }
}
