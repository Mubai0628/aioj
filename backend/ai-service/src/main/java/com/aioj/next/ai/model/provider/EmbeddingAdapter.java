package com.aioj.next.ai.model.provider;

import com.aioj.next.ai.model.api.EmbeddingResponse;
import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ModelException;
import com.aioj.next.ai.model.api.ModelUsage;
import com.aioj.next.ai.model.api.ProviderCapabilities;
import com.aioj.next.ai.model.api.ProviderId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** OpenAI-compatible embeddings, as DashScope's compatible mode serves them. */
public final class EmbeddingAdapter {

    public ProviderCapabilities capabilities(ProviderId provider, String model) {
        return new ProviderCapabilities(provider, model, false, true, false, false, false, false, false, false,
                false, false, provider == ProviderId.OPENAI_COMPATIBLE);
    }

    /** Accepts the endpoint itself, the compatible-mode root, a /v1 root or a chat-completions URL. */
    public URI uri(ModelEndpointConfig endpoint) {
        URI configured = ProviderErrors.endpointUri(endpoint.baseUrl(), endpoint.provider(), endpoint.model());
        String url = configured.toString();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/embeddings")) {
            return URI.create(url);
        }
        if (lower.endsWith("/chat/completions")) {
            return URI.create(url.substring(0, url.length() - "/chat/completions".length()) + "/embeddings");
        }
        if (lower.endsWith("/compatible-mode")) {
            return URI.create(url + "/v1/embeddings");
        }
        return URI.create(url + "/embeddings");
    }

    public ObjectNode body(ModelEndpointConfig endpoint, List<String> inputs) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("model", endpoint.model());
        if (inputs.size() == 1) {
            body.put("input", inputs.get(0));
        } else {
            ArrayNode array = body.putArray("input");
            inputs.forEach(array::add);
        }
        body.put("encoding_format", "float");
        if (endpoint.embeddingDimension() != null && endpoint.embeddingDimension() > 0) {
            body.put("dimensions", endpoint.embeddingDimension());
        }
        return body;
    }

    public EmbeddingResponse parse(String body, ModelEndpointConfig endpoint, int expected) {
        JsonNode root = ProviderErrors.readJson(body, endpoint.provider(), endpoint.model());
        Map<Integer, List<Double>> byIndex = new TreeMap<>();
        int position = 0;
        for (JsonNode item : root.path("data")) {
            JsonNode vector = item.path("embedding");
            if (!vector.isArray() || vector.isEmpty()) {
                throw ProviderErrors.invalidResponse(endpoint.provider(), endpoint.model());
            }
            List<Double> values = new ArrayList<>(vector.size());
            vector.forEach(value -> values.add(value.asDouble(0.0)));
            byIndex.put(item.path("index").asInt(position), values);
            position++;
        }
        if (byIndex.size() != expected) {
            throw ProviderErrors.invalidResponse(endpoint.provider(), endpoint.model());
        }
        JsonNode usage = root.path("usage");
        long promptTokens = usage.path("prompt_tokens").asLong(usage.path("total_tokens").asLong(0));
        boolean reported = usage.has("prompt_tokens") || usage.has("total_tokens");
        return new EmbeddingResponse(new ArrayList<>(byIndex.values()), new ModelUsage(promptTokens, 0, 0, reported),
                endpoint.provider(), endpoint.model());
    }

    public ModelException classify(int status, String body, Duration retryAfter, ModelEndpointConfig endpoint) {
        return ProviderErrors.httpFailure(endpoint.provider(), endpoint.model(), status,
                ProviderErrors.errorType(body), retryAfter);
    }
}
