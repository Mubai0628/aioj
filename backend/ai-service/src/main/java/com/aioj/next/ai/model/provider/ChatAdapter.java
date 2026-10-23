package com.aioj.next.ai.model.provider;

import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ModelException;
import com.aioj.next.ai.model.api.ModelRequest;
import com.aioj.next.ai.model.api.ModelResponse;
import com.aioj.next.ai.model.api.ProviderCapabilities;
import com.aioj.next.ai.model.api.ProviderId;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Duration;

/**
 * One provider's chat dialect (plan §3.2.3 ProviderAdapter): every difference
 * between providers lives behind this interface.
 */
public interface ChatAdapter {

    ProviderId id();

    ProviderCapabilities capabilities(String model);

    URI chatUri(ModelEndpointConfig endpoint);

    ObjectNode chatBody(ModelEndpointConfig endpoint, CallSettings settings, ModelRequest request, boolean stream);

    ModelResponse parseChat(String body, ModelEndpointConfig endpoint);

    void onStreamEvent(String data, StreamAccumulator accumulator);

    /** The typed failure for a non-2xx answer; the body is read for classification only. */
    ModelException classify(int status, String body, Duration retryAfter, ModelEndpointConfig endpoint,
                            CallSettings settings);
}
