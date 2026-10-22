package com.aioj.next.ai.domain;

import com.aioj.next.contract.ai.AiChatRequest;

import java.util.List;
import java.util.Optional;

public interface AiProvider {
    AiCompletion chat(AiChatRequest request);

    default AiCompletion chat(AiChatRequest request, AiChatContext context) {
        return chat(request);
    }

    default AiCompletion chat(AiChatRequest request, AiChatContext context, AiModelScope scope) {
        return chat(request, context);
    }

    default List<AiCompletion.MemorySignal> extractMemories(String userMessage, String assistantMessage) {
        return List.of();
    }

    default Optional<List<Double>> embed(String input) {
        return Optional.empty();
    }

    /**
     * The exact user-side content that will be sent to the model for a chat turn (without
     * the static system prompt). Leak detection must inspect this same text so the checked
     * surface always matches what the model actually receives.
     */
    default String assistantInputPreview(AiChatRequest request, AiChatContext context) {
        return request == null || request.message() == null ? "" : request.message();
    }

    String providerName();

    String model();
}
