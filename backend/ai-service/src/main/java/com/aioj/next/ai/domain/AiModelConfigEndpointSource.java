package com.aioj.next.ai.domain;

import com.aioj.next.ai.model.api.ModelConfigSource;
import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ModelErrorKind;
import com.aioj.next.ai.model.api.ModelException;
import com.aioj.next.ai.model.api.ProviderId;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Feeds the model call package from ai_model_configs and the API keys in the
 * server environment (plan §3.2.3): the assembly-layer side of
 * {@link ModelConfigSource}.
 */
@Component
public class AiModelConfigEndpointSource implements ModelConfigSource {
    private final AiModelConfigService configService;

    public AiModelConfigEndpointSource(AiModelConfigService configService) {
        this.configService = configService;
    }

    @Override
    public ModelEndpointConfig resolve(String scope) {
        AiModelScope modelScope;
        try {
            modelScope = AiModelScope.from(scope);
        } catch (IllegalArgumentException ex) {
            throw new ModelException(ModelErrorKind.CONFIGURATION_INVALID, null, null,
                    "Unknown AI model scope: " + scope);
        }
        return toEndpoint(configService.effectiveConfig(modelScope));
    }

    /** The same conversion for a configuration that is not stored yet, such as one under test. */
    public static ModelEndpointConfig toEndpoint(AiModelEffectiveConfig config) {
        ProviderId provider = config.scope() == AiModelScope.EMBEDDING
                ? embeddingProvider(config.provider())
                : ProviderId.fromConfig(config.provider());
        return new ModelEndpointConfig(provider, config.baseUrl(), config.apiKey(), config.model(), config.enabled(),
                config.jsonOutputEnabled(), config.thinkingEnabled(), config.reasoningEffort(), config.temperature(),
                config.maxTokens(), config.embeddingDimension());
    }

    /** The admin settings offer DashScope or any custom OpenAI-compatible endpoint for embeddings. */
    private static ProviderId embeddingProvider(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "dashscope", "qwen", "alibaba" -> ProviderId.DASHSCOPE;
            default -> ProviderId.OPENAI_COMPATIBLE;
        };
    }
}
