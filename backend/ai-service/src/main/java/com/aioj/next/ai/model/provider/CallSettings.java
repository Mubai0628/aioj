package com.aioj.next.ai.model.provider;

import com.aioj.next.ai.model.api.CallProfile;
import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ModelRequest;
import com.aioj.next.ai.model.api.ProviderCapabilities;
import com.aioj.next.ai.model.api.ResponseFormat;

import java.util.Locale;

/**
 * Per-call settings after the call profile's safety defaults are applied to the
 * endpoint configuration.
 *
 * @param reasoningEffort {@code high} or {@code max}
 */
public record CallSettings(boolean thinking, String reasoningEffort, double temperature, int maxTokens,
                           ResponseFormat responseFormat) {

    public static CallSettings resolve(ModelEndpointConfig endpoint, ModelRequest request,
                                       ProviderCapabilities capabilities) {
        CallProfile profile = request.profile();
        boolean thinking = capabilities.thinks(endpoint, profile, request.thinkingDisabled());
        int maxTokens = endpoint.maxTokens() == null || endpoint.maxTokens() <= 0
                ? profile.defaultMaxTokens()
                : endpoint.maxTokens();
        if (thinking) {
            maxTokens = Math.max(maxTokens, CallProfile.MIN_MAX_TOKENS_WHEN_THINKING);
        }
        double temperature = endpoint.temperature() == null ? profile.defaultTemperature() : endpoint.temperature();
        String effort = endpoint.reasoningEffort() != null
                && "max".equals(endpoint.reasoningEffort().trim().toLowerCase(Locale.ROOT)) ? "max" : "high";
        ResponseFormat format = request.responseFormat();
        if (!format.isJson() && profile.forceJsonOutput()) {
            format = ResponseFormat.jsonObject();
        }
        return new CallSettings(thinking, effort, temperature, maxTokens, format);
    }
}
