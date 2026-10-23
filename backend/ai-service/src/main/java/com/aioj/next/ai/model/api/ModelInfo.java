package com.aioj.next.ai.model.api;

/** One entry of a provider's model catalog. */
public record ModelInfo(String id, String ownedBy, Integer contextLength, boolean supportsReasoning) {
}
