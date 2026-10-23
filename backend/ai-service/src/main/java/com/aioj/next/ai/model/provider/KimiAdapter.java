package com.aioj.next.ai.model.provider;

import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ModelErrorKind;
import com.aioj.next.ai.model.api.ModelException;
import com.aioj.next.ai.model.api.ProviderCapabilities;
import com.aioj.next.ai.model.api.ProviderId;
import com.aioj.next.ai.model.api.ToolSpec;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.Locale;

/**
 * Kimi (platform.kimi.com), rules per model family (appendix G.4 item 6):
 * <ul>
 *   <li>K3 always thinks, takes {@code reasoning_effort} and no thinking field, uses
 *       {@code max_completion_tokens}, takes no temperature and is the only one with
 *       native {@code tool_choice=required}.</li>
 *   <li>K2.5 and K2.6 switch thinking with a {@code thinking} field; K2.6 takes no
 *       temperature, K2.5 only 1.0 while thinking.</li>
 *   <li>K2.7-code only works with thinking on and takes no temperature.</li>
 * </ul>
 * {@code strict} is set per function, default true. Streams ask for usage
 * explicitly; it arrives in an extra chunk with empty {@code choices}.
 */
public final class KimiAdapter extends OpenAiCompatibleAdapter {

    @Override
    public ProviderId id() {
        return ProviderId.KIMI;
    }

    @Override
    public ProviderCapabilities capabilities(String model) {
        Family family = Family.of(model);
        boolean alwaysThinks = family == Family.K3 || family == Family.K2_7_CODE;
        boolean toggle = family == Family.K2_5 || family == Family.K2_6;
        return new ProviderCapabilities(ProviderId.KIMI, model, true, false, true, family == Family.K3, true, true,
                true, false, toggle, alwaysThinks, true);
    }

    @Override
    protected void streamOptions(ObjectNode body) {
        body.putObject("stream_options").put("include_usage", true);
    }

    @Override
    protected void maxTokens(ObjectNode body, ModelEndpointConfig endpoint, CallSettings settings) {
        body.put(Family.of(endpoint.model()) == Family.K3 ? "max_completion_tokens" : "max_tokens",
                settings.maxTokens());
    }

    @Override
    protected void temperature(ObjectNode body, ModelEndpointConfig endpoint, CallSettings settings) {
        switch (Family.of(endpoint.model())) {
            case K3, K2_6, K2_7_CODE -> {
                // These models fix their own sampling and reject an explicit temperature.
            }
            case K2_5 -> body.put("temperature", settings.thinking() ? 1.0 : 0.6);
            default -> body.put("temperature", settings.temperature());
        }
    }

    @Override
    protected void thinking(ObjectNode body, ModelEndpointConfig endpoint, CallSettings settings) {
        switch (Family.of(endpoint.model())) {
            case K3 -> body.put("reasoning_effort", settings.reasoningEffort());
            case K2_5, K2_6 -> body.putObject("thinking").put("type", settings.thinking() ? "enabled" : "disabled");
            case K2_7_CODE -> body.putObject("thinking").put("type", "enabled");
            default -> {
                // Models without thinking take no thinking controls.
            }
        }
    }

    @Override
    protected void functionFlags(ObjectNode function, ToolSpec spec) {
        function.put("strict", spec.strict() == null || spec.strict());
    }

    /** Kimi says "no balance" with HTTP 429; it must not be retried like a rate limit. */
    @Override
    protected ModelException refine(int status, String errorType, String body, Duration retryAfter,
                                    ModelEndpointConfig endpoint, CallSettings settings) {
        if (status == 429 && "exceeded_current_quota_error".equals(errorType)) {
            return new ModelException(ModelErrorKind.QUOTA_EXHAUSTED, id(), endpoint.model(),
                    "AI provider kimi account balance or quota is exhausted (HTTP 429)", status, errorType,
                    null, false);
        }
        return null;
    }

    private enum Family {
        K3,
        K2_5,
        K2_6,
        K2_7_CODE,
        OTHER;

        static Family of(String model) {
            String normalized = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("kimi-k3")) {
                return K3;
            }
            if (normalized.startsWith("kimi-k2.7-code")) {
                return K2_7_CODE;
            }
            if (normalized.startsWith("kimi-k2.6")) {
                return K2_6;
            }
            if (normalized.startsWith("kimi-k2.5")) {
                return K2_5;
            }
            return OTHER;
        }
    }
}
