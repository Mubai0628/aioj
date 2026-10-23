package com.aioj.next.ai.model.api;

/**
 * One model-initiated tool call. {@code name} is always the internal dotted name;
 * the provider adapters handle the wire encoding.
 */
public record ToolCall(String id, String name, String argumentsJson) {
}
