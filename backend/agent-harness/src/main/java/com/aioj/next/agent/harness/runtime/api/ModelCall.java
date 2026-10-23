package com.aioj.next.agent.harness.runtime.api;

import java.time.Duration;
import java.util.List;

/**
 * One request to the model. {@code profile} is opaque to the Harness: the
 * adapter maps it to its own call profile (thinking, token floor, JSON mode).
 * {@code timeout} is the time left in the run, or null without a run
 * deadline. Adapters should end the HTTP exchange when {@code cancellation}
 * fires, so the Provider stops generating (plan appendix G, item 6).
 */
public record ModelCall(List<HarnessMessage> messages, List<ModelTool> tools, ToolChoice toolChoice, String profile,
                        Duration timeout, CancellationToken cancellation) {
    public ModelCall {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        toolChoice = toolChoice == null ? ToolChoice.AUTO : toolChoice;
        cancellation = cancellation == null ? CancellationToken.none() : cancellation;
    }
}
