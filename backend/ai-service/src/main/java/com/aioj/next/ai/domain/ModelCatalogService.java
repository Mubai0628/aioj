package com.aioj.next.ai.domain;

import com.aioj.next.ai.model.api.CallOptions;
import com.aioj.next.ai.model.api.ModelClient;
import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ModelErrorKind;
import com.aioj.next.ai.model.api.ModelException;
import com.aioj.next.ai.model.api.ModelInfo;
import com.aioj.next.ai.model.api.ProviderId;
import com.aioj.next.contract.ai.AiModelListResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Provider model catalogs for the admin model settings, on {@link ModelClient}
 * (plan T1.3): fetches the catalog and describes each model's JSON, thinking and
 * temperature rules for the settings page.
 */
@Component
public class ModelCatalogService {
    private final ModelClient modelClient;

    public ModelCatalogService(ModelClient modelClient) {
        this.modelClient = modelClient;
    }

    public AiModelListResponse listModels(AiModelScope scope, String provider, String baseUrl,
                                           EnvironmentAiModelConfigResolver.ApiKeyResolution key) {
        String normalizedProvider = normalizeProvider(provider);
        ProviderId providerId = providerId(scope, normalizedProvider);
        if (providerId == ProviderId.DASHSCOPE) {
            return response(scope, normalizedProvider, baseUrl, key, "UNSUPPORTED",
                    "This provider does not expose a compatible model list API.", List.of());
        }
        if (key == null || !key.hasApiKey()) {
            return response(scope, normalizedProvider, baseUrl, key, "MISSING_KEY",
                    "Server environment API key is not configured for this provider.", List.of());
        }
        ModelEndpointConfig candidate = ModelEndpointConfig.builder(providerId).baseUrl(baseUrl)
                .apiKey(key.apiKey()).build();
        try {
            List<AiModelListResponse.ModelOption> models = modelClient
                    .listModels(candidate, CallOptions.of("admin.model-catalog")).stream()
                    .map(model -> modelOption(normalizedProvider, model))
                    .toList();
            return response(scope, normalizedProvider, baseUrl, key, "SUCCESS", null, models);
        } catch (ModelException ex) {
            String status = ex.kind() == ModelErrorKind.CAPABILITY_UNSUPPORTED ? "UNSUPPORTED" : "FAILED";
            return response(scope, normalizedProvider, baseUrl, key, status, ex.getMessage(), List.of());
        }
    }

    /** What the settings page may offer for one catalog entry. */
    static AiModelListResponse.ModelOption modelOption(String provider, ModelInfo info) {
        String id = info.id();
        String ownedBy = info.ownedBy() == null ? "" : info.ownedBy();
        String model = id.toLowerCase(Locale.ROOT);
        if ("deepseek".equals(provider) || model.startsWith("deepseek-")) {
            boolean deprecated = "deepseek-chat".equals(model) || "deepseek-reasoner".equals(model);
            boolean supportsThinking = model.startsWith("deepseek-v4-") || deprecated;
            return new AiModelListResponse.ModelOption(id, ownedBy, true, supportsThinking,
                    supportsThinking ? List.of("high", "max") : List.of(), null, null, info.contextLength(),
                    deprecated);
        }
        if ("moonshot".equals(provider) || "kimi".equals(provider)
                || ownedBy.toLowerCase(Locale.ROOT).contains("moonshot")) {
            // K3 always thinks and takes an effort level, so it only accepts temperature 1.
            boolean k3 = model.startsWith("kimi-k3");
            boolean kimiThinking = info.supportsReasoning() || model.startsWith("kimi-k2.5")
                    || model.startsWith("kimi-k2.6") || k3;
            return new AiModelListResponse.ModelOption(id, ownedBy, true, kimiThinking,
                    k3 ? List.of("high", "max") : List.of(), null,
                    k3 ? Double.valueOf(1.0) : kimiThinking ? Double.valueOf(0.6) : null, info.contextLength(),
                    false);
        }
        return new AiModelListResponse.ModelOption(id, ownedBy, false, false, List.of(), null, null,
                info.contextLength(), false);
    }

    /** Text scopes are DeepSeek or Kimi; embeddings are DashScope or a custom OpenAI-compatible endpoint. */
    private static ProviderId providerId(AiModelScope scope, String provider) {
        if (scope != AiModelScope.EMBEDDING) {
            switch (provider) {
                case "deepseek":
                    return ProviderId.DEEPSEEK;
                case "kimi", "moonshot":
                    return ProviderId.KIMI;
                default:
                    break;
            }
        }
        return switch (provider) {
            case "dashscope", "qwen", "alibaba" -> ProviderId.DASHSCOPE;
            case "deepseek" -> ProviderId.DEEPSEEK;
            case "kimi", "moonshot" -> ProviderId.KIMI;
            default -> ProviderId.OPENAI_COMPATIBLE;
        };
    }

    private static String normalizeProvider(String provider) {
        return provider == null || provider.isBlank() ? "custom" : provider.trim().toLowerCase(Locale.ROOT);
    }

    private static AiModelListResponse response(AiModelScope scope, String provider, String baseUrl,
                                                EnvironmentAiModelConfigResolver.ApiKeyResolution key,
                                                String status, String error,
                                                List<AiModelListResponse.ModelOption> models) {
        return new AiModelListResponse(
                scope.name(),
                provider,
                baseUrl == null ? "" : baseUrl.trim(),
                key != null && key.hasApiKey(),
                key == null || key.apiKeyEnvName() == null ? "" : key.apiKeyEnvName(),
                true,
                status,
                error,
                models
        );
    }
}
