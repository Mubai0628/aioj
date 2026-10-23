package com.aioj.next.ai.model.provider;

import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ProviderCapabilities;
import com.aioj.next.ai.model.api.ProviderId;
import tools.jackson.databind.node.ObjectNode;

/**
 * Any other OpenAI-compatible chat endpoint (plan Q13), for the text scopes that make plain
 * completions: the standard parameters, JSON object output and streaming. It calls no tools
 * and has no thinking controls, since those are switched on only for providers whose
 * behaviour has been verified (DeepSeek and Kimi).
 */
public final class GenericOpenAiAdapter extends OpenAiCompatibleAdapter {

    @Override
    public ProviderId id() {
        return ProviderId.OPENAI_COMPATIBLE;
    }

    @Override
    public ProviderCapabilities capabilities(String model) {
        return new ProviderCapabilities(ProviderId.OPENAI_COMPATIBLE, model, true, true, false, false, false, true,
                true, false, false, false, true);
    }

    @Override
    protected void temperature(ObjectNode body, ModelEndpointConfig endpoint, CallSettings settings) {
        body.put("temperature", settings.temperature());
    }

    @Override
    protected void thinking(ObjectNode body, ModelEndpointConfig endpoint, CallSettings settings) {
        // No thinking controls: every provider names them differently.
    }
}
