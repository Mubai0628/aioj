package com.aioj.next.ai.domain;

import com.aioj.next.ai.model.api.CallOptions;
import com.aioj.next.ai.model.api.CallProfile;
import com.aioj.next.ai.model.api.ChatMessage;
import com.aioj.next.ai.model.api.EmbeddingRequest;
import com.aioj.next.ai.model.api.EmbeddingResponse;
import com.aioj.next.ai.model.api.ModelClient;
import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ModelRequest;
import com.aioj.next.ai.model.api.ModelResponse;
import com.aioj.next.ai.model.api.ProviderCapabilities;
import com.aioj.next.ai.model.api.ResponseFormat;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One-off text completions and embedding probes for business code outside the
 * Agent loop, on {@link ModelClient} (plan T1.3). It keeps the contract the old
 * completion client gave its callers: JSON output only where the configuration
 * allows it, one retry without thinking when a thinking call comes back blank,
 * and token counts estimated when the provider reports none.
 */
@Component
public class ModelCompletions {
    private final ModelClient modelClient;

    public ModelCompletions(ModelClient modelClient) {
        this.modelClient = modelClient;
    }

    /**
     * @param messages role/content pairs; the roles are {@code system}, {@code user} and {@code assistant}
     */
    public Result complete(AiModelEffectiveConfig config, List<Map<String, String>> messages,
                           double temperature, int maxTokens, boolean jsonOutput) {
        if (config == null || !config.enabled()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "AI model configuration is disabled");
        }
        if (!config.hasApiKey()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "AI API key is not configured");
        }
        ModelEndpointConfig endpoint = AiModelConfigEndpointSource.toEndpoint(config);
        ProviderCapabilities capabilities = modelClient.capabilities(endpoint);
        ModelRequest request = ModelRequest.builder(config.scope().name())
                .endpoint(endpoint)
                .messages(chatMessages(messages))
                .profile(CallProfile.COMPLETION)
                .temperature(temperature)
                .maxTokens(maxTokens > 0 ? maxTokens : null)
                .responseFormat(jsonOutput && endpoint.jsonOutputEnabled() && capabilities.jsonObject()
                        ? ResponseFormat.jsonObject() : ResponseFormat.text())
                .build();
        CallOptions options = CallOptions.of("completion." + config.scope().name().toLowerCase(Locale.ROOT));
        ModelResponse response = modelClient.complete(request, options);
        if (!response.content().isBlank()) {
            return result(response, messages);
        }
        boolean canRetryWithoutThinking = endpoint.tuning().thinkingFallbackAllowed()
                && capabilities.thinkingToggle()
                && capabilities.thinks(endpoint, CallProfile.COMPLETION, false);
        if (!canRetryWithoutThinking) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "AI provider returned empty content");
        }
        // Thinking can use up the whole budget and leave no answer; one call without it.
        ModelResponse retry = modelClient.complete(request.withThinkingDisabled(), options);
        if (retry.content().isBlank()) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "AI provider returned empty content");
        }
        Result first = result(response, messages);
        Result second = result(retry, messages);
        return new Result(second.content(), second.provider(), second.model(),
                first.promptTokens() + second.promptTokens(), first.completionTokens() + second.completionTokens());
    }

    /** Sends an embeddings request for the admin connectivity test of an embedding configuration. */
    public EmbeddingProbe embedProbe(AiModelEffectiveConfig config, String input) {
        if (config == null || !config.enabled()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "AI model configuration is disabled");
        }
        ModelEndpointConfig endpoint = AiModelConfigEndpointSource.toEndpoint(config);
        String text = input == null || input.isBlank() ? "ai-model-config-embedding-test" : input.trim();
        EmbeddingResponse response = modelClient.embed(
                new EmbeddingRequest(config.scope().name(), endpoint, List.of(text)),
                CallOptions.of("completion.embedding-probe"));
        if (response.first().isEmpty()) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "AI provider returned no embedding vector");
        }
        return new EmbeddingProbe(response.first().size(), response.usage().promptTokens());
    }

    private static List<ChatMessage> chatMessages(List<Map<String, String>> messages) {
        List<ChatMessage> converted = new ArrayList<>(messages.size());
        for (Map<String, String> message : messages) {
            String role = message.getOrDefault("role", "user");
            String content = message.get("content");
            converted.add(switch (role) {
                case "system" -> ChatMessage.system(content);
                case "assistant" -> ChatMessage.assistant(content);
                default -> ChatMessage.user(content);
            });
        }
        return converted;
    }

    private static Result result(ModelResponse response, List<Map<String, String>> messages) {
        long promptTokens = response.usage().reported()
                ? response.usage().promptTokens() : estimateTokens(messages.toString());
        long completionTokens = response.usage().reported()
                ? response.usage().completionTokens() : estimateTokens(response.content());
        return new Result(response.content(), response.provider() == null ? null : response.provider().configName(),
                response.model(), promptTokens, completionTokens);
    }

    private static long estimateTokens(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return Math.max(1, (value.length() + 3L) / 4L);
    }

    public record Result(String content, String provider, String model, long promptTokens, long completionTokens) {
    }

    public record EmbeddingProbe(int dimension, long promptTokens) {
    }
}
