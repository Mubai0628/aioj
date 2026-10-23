package com.aioj.next.ai.model.api;

/**
 * Supplies endpoint configuration by scope. Implemented outside the package by
 * ai-service's assembly layer, on top of ai_model_configs and the API keys in
 * the server environment (plan §3.2.3).
 */
public interface ModelConfigSource {

    /** @param scope an ai_model_configs scope name, such as {@code TEXT_GENERATION} */
    ModelEndpointConfig resolve(String scope);
}
