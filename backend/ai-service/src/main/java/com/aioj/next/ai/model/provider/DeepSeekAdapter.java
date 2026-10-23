package com.aioj.next.ai.model.provider;

import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ModelErrorKind;
import com.aioj.next.ai.model.api.ModelException;
import com.aioj.next.ai.model.api.ProviderCapabilities;
import com.aioj.next.ai.model.api.ProviderId;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;

/**
 * DeepSeek (api-docs.deepseek.com). Thinking is switched per request and takes
 * {@code reasoning_effort}; a thinking request takes no temperature. Thinking
 * together with {@code tool_choice=required} is answered with HTTP 400, so the
 * Agent loop always simulates REQUIRED here. The stream's usage arrives on the
 * chunk that carries {@code finish_reason}, without any stream option.
 */
public final class DeepSeekAdapter extends OpenAiCompatibleAdapter {

    @Override
    public ProviderId id() {
        return ProviderId.DEEPSEEK;
    }

    @Override
    public ProviderCapabilities capabilities(String model) {
        return new ProviderCapabilities(ProviderId.DEEPSEEK, model, true, false, true, false, false, true, true,
                false, true, false, true);
    }

    @Override
    protected void temperature(ObjectNode body, ModelEndpointConfig endpoint, CallSettings settings) {
        if (!settings.thinking()) {
            body.put("temperature", settings.temperature());
        }
    }

    @Override
    protected void thinking(ObjectNode body, ModelEndpointConfig endpoint, CallSettings settings) {
        body.putObject("thinking").put("type", settings.thinking() ? "enabled" : "disabled");
        if (settings.thinking()) {
            body.put("reasoning_effort", settings.reasoningEffort());
        }
    }

    /** A model that cannot think in this mode becomes THINKING_UNAVAILABLE, so the caller may fall back. */
    @Override
    protected ModelException refine(int status, String errorType, String body, Duration retryAfter,
                                    ModelEndpointConfig endpoint, CallSettings settings) {
        if (!settings.thinking() || (status != 400 && status != 422)) {
            return null;
        }
        boolean aboutThinking = ProviderErrors.mentions(body, "thinking", "reasoning_content", "reasoning mode");
        boolean capabilityRejection = ProviderErrors.mentions(body, "unsupported", "not support", "not available",
                "unknown parameter", "invalid parameter");
        if (!aboutThinking || !capabilityRejection) {
            return null;
        }
        return new ModelException(ModelErrorKind.THINKING_UNAVAILABLE, id(), endpoint.model(),
                "Configured AI model does not support the requested thinking mode", status, errorType, null, false);
    }
}
