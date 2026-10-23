package com.aioj.next.ai.model.api;

import java.util.List;

/**
 * The only way into the model call package (plan §3.2.1). Every LLM and embedding
 * call in ai-service goes through it: configuration, capability checks, the scope
 * bulkhead, the circuit breaker, retries and the deadline are applied here, and
 * every call is reported to the {@link ModelCallListener}s once.
 */
public interface ModelClient {

    /**
     * The endpoint a scope is configured with. A caller that makes several calls in
     * one run (the Agent loop) resolves once and puts the result on each request, so
     * a configuration change mid-run cannot mix two models in one tool loop.
     */
    ModelEndpointConfig resolve(String scope);

    /** What the endpoint's provider and model support; illegal combinations fail before any HTTP call. */
    ProviderCapabilities capabilities(ModelEndpointConfig endpoint);

    ModelResponse complete(ModelRequest request, CallOptions options);

    /**
     * Streams on the calling thread and returns the aggregated response once the
     * provider finishes. Text deltas reach the listener as they arrive; reasoning
     * deltas stay in memory and only come back on the response, for the tool loop.
     * Cancelling {@link CallOptions#cancellation()} closes the connection.
     */
    ModelResponse stream(ModelRequest request, CallOptions options, ModelStreamListener listener);

    EmbeddingResponse embed(EmbeddingRequest request, CallOptions options);

    /** The provider's model catalog for a candidate endpoint, for the admin model settings. */
    List<ModelInfo> listModels(ModelEndpointConfig candidate, CallOptions options);
}
