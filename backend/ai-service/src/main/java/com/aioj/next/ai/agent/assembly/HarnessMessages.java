package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.runtime.api.HarnessMessage;
import com.aioj.next.agent.harness.tool.api.ToolCallRequest;
import com.aioj.next.ai.model.api.ChatMessage;
import com.aioj.next.ai.model.api.ToolCall;

import java.util.ArrayList;
import java.util.List;

/** Converts conversations between the Harness loop and the model call package, both ways. */
final class HarnessMessages {
    private HarnessMessages() {
    }

    static List<ChatMessage> toChat(List<HarnessMessage> messages) {
        List<ChatMessage> converted = new ArrayList<>(messages.size());
        for (HarnessMessage message : messages) {
            converted.add(switch (message.role()) {
                case SYSTEM -> ChatMessage.system(message.content());
                case USER -> ChatMessage.user(message.content());
                case ASSISTANT -> ChatMessage.assistant(message.content(), message.reasoningContent(),
                        message.toolCalls().stream()
                                .map(call -> new ToolCall(call.callId(), call.toolName(), call.argumentsJson()))
                                .toList());
                case TOOL -> ChatMessage.toolResult(message.toolCallId(), message.content());
            });
        }
        return converted;
    }

    static List<HarnessMessage> fromChat(List<ChatMessage> messages) {
        List<HarnessMessage> converted = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            converted.add(switch (message.role()) {
                case SYSTEM -> HarnessMessage.system(message.content());
                case USER -> HarnessMessage.user(message.content());
                case ASSISTANT -> HarnessMessage.assistant(message.content(), message.reasoningContent(),
                        message.toolCalls().stream()
                                .map(call -> new ToolCallRequest(call.id(), call.name(), call.argumentsJson()))
                                .toList());
                case TOOL -> HarnessMessage.toolResult(message.toolCallId(), message.content());
            });
        }
        return converted;
    }
}
