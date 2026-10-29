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
import java.util.Set;

/**
 * Provider model catalogs for the admin model settings, on {@link ModelClient}
 * (plan T1.3): fetches the catalog and describes each model's JSON, thinking and
 * temperature rules for the settings page.
 */
@Component
public class ModelCatalogService {
    static final List<String> DEEPSEEK_EFFORTS = List.of("low", "high", "max");
    /** Names DeepSeek has retired but still serves (2026-10-29); the settings page flags them. */
    static final Set<String> RETIRED_DEEPSEEK_MODELS = Set.of("deepseek-chat", "deepseek-reasoner",
            "deepseek-v4-flash", "deepseek-v4-flash-vision-exp");
    /** Retired on 2026-10-29 for text scopes; embeddings keep openai-compatible. */
    private static final Set<String> RETIRED_TEXT_PROVIDERS = Set.of("kimi", "moonshot", "openai-compatible",
            "openai", "custom");

    private final ModelClient modelClient;

    public ModelCatalogService(ModelClient modelClient) {
        this.modelClient = modelClient;
    }

    public AiModelListResponse listModels(AiModelScope scope, String provider, String baseUrl,
                                           EnvironmentAiModelConfigResolver.ApiKeyResolution key) {
        String normalizedProvider = normalizeProvider(provider);
        if (scope != AiModelScope.EMBEDDING && RETIRED_TEXT_PROVIDERS.contains(normalizedProvider)) {
            return response(scope, normalizedProvider, baseUrl, key, "UNSUPPORTED",
                    "This provider was retired on 2026-10-29; text scopes use deepseek.", List.of());
        }
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
            // DeepSeek's catalog names models but not their abilities. Since 2026-10-29 every DeepSeek model
            // counts as thinking-capable (the official deepseek-flash replaced deepseek-v4-flash, which a
            // "deepseek-v4-" rule missed); effort levels are the documented low, high and max.
            boolean supportsThinking = model.startsWith("deepseek");
            return new AiModelListResponse.ModelOption(id, ownedBy, true, supportsThinking,
                    supportsThinking ? DEEPSEEK_EFFORTS : List.of(), null, null, info.contextLength(),
                    RETIRED_DEEPSEEK_MODELS.contains(model));
        }
        // Kimi, retired on 2026-10-29:
        // if ("moonshot".equals(provider) || "kimi".equals(provider)
        //         || ownedBy.toLowerCase(Locale.ROOT).contains("moonshot")) {
        //     // K3 always thinks and takes an effort level, so it only accepts temperature 1.
        //     boolean k3 = model.startsWith("kimi-k3");
        //     boolean kimiThinking = info.supportsReasoning() || model.startsWith("kimi-k2.5")
        //             || model.startsWith("kimi-k2.6") || k3;
        //     return new AiModelListResponse.ModelOption(id, ownedBy, true, kimiThinking,
        //             k3 ? List.of("high", "max") : List.of(), null,
        //             k3 ? Double.valueOf(1.0) : kimiThinking ? Double.valueOf(0.6) : null, info.contextLength(),
        //             false);
        // }
        return new AiModelListResponse.ModelOption(id, ownedBy, false, false, List.of(), null, null,
                info.contextLength(), false);
    }

    /** Text scopes are DeepSeek; embeddings are DashScope or a custom OpenAI-compatible endpoint. */
    private static ProviderId providerId(AiModelScope scope, String provider) {
        if (scope != AiModelScope.EMBEDDING) {
            switch (provider) {
                case "deepseek":
                    return ProviderId.DEEPSEEK;
                // Kimi, retired on 2026-10-29 (listModels answers for it before this point):
                // case "kimi", "moonshot":
                //     return ProviderId.KIMI;
                default:
                    break;
            }
        }
        return switch (provider) {
            case "dashscope", "qwen", "alibaba" -> ProviderId.DASHSCOPE;
            case "deepseek" -> ProviderId.DEEPSEEK;
            // Kimi, retired on 2026-10-29: case "kimi", "moonshot" -> ProviderId.KIMI;
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
