package com.aioj.next.ai.model.provider;

import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ModelErrorKind;
import com.aioj.next.ai.model.api.ModelException;
import com.aioj.next.ai.model.api.ModelInfo;
import com.aioj.next.ai.model.api.ProviderId;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The providers' {@code GET /models} catalogs, for the admin model settings. */
public final class ModelCatalog {

    /** DeepSeek serves it at the host root, Kimi under /v1; DashScope's compatible mode has none. */
    public URI modelsUri(ModelEndpointConfig endpoint) {
        URI configured = ProviderErrors.endpointUri(endpoint.baseUrl(), endpoint.provider(), endpoint.model());
        return switch (endpoint.provider()) {
            case DEEPSEEK -> URI.create(ProviderErrors.origin(configured) + "/models");
            case KIMI -> URI.create(ProviderErrors.origin(configured) + "/v1/models");
            case DASHSCOPE -> throw new ModelException(ModelErrorKind.CAPABILITY_UNSUPPORTED, ProviderId.DASHSCOPE,
                    endpoint.model(), "This provider does not expose a compatible model list API.");
            case OPENAI_COMPATIBLE -> derived(configured);
        };
    }

    public ModelException classify(int status, String body, java.time.Duration retryAfter,
                                   ModelEndpointConfig endpoint) {
        return ProviderErrors.httpFailure(endpoint.provider(), endpoint.model(), status,
                ProviderErrors.errorType(body), retryAfter);
    }

    public List<ModelInfo> parse(String body, ModelEndpointConfig endpoint) {
        JsonNode data = ProviderErrors.readJson(body, endpoint.provider(), endpoint.model()).path("data");
        if (!data.isArray()) {
            throw ProviderErrors.invalidResponse(endpoint.provider(), endpoint.model());
        }
        List<ModelInfo> models = new ArrayList<>();
        for (JsonNode item : data) {
            String id = item.path("id").asString("");
            if (id.isBlank()) {
                continue;
            }
            JsonNode context = item.path("context_length");
            models.add(new ModelInfo(id, item.path("owned_by").asString(""),
                    context.canConvertToInt() ? context.asInt(0) : null,
                    item.path("supports_reasoning").asBoolean(false)));
        }
        return models;
    }

    private static URI derived(URI configured) {
        String url = configured.toString();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/chat/completions")) {
            return URI.create(url.substring(0, url.length() - "/chat/completions".length()) + "/models");
        }
        if (lower.endsWith("/embeddings")) {
            return URI.create(url.substring(0, url.length() - "/embeddings".length()) + "/models");
        }
        return URI.create(url + "/models");
    }
}
