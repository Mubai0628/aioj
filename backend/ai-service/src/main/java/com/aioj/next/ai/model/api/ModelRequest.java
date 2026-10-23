package com.aioj.next.ai.model.api;

import java.util.ArrayList;
import java.util.List;

/**
 * One chat request.
 *
 * @param scope            ai_model_configs scope; picks the configuration (unless {@code endpoint}
 *                         is given) and the concurrency bulkhead
 * @param endpoint         an endpoint the caller already resolved or is testing; {@code null}
 *                         resolves {@code scope} through the {@link ModelConfigSource}
 * @param thinkingDisabled the controlled standard fallback: thinking off for this call only,
 *                         without touching the stored configuration
 */
public record ModelRequest(String scope, ModelEndpointConfig endpoint, List<ChatMessage> messages,
                           List<ToolSpec> tools, ToolChoice toolChoice, ResponseFormat responseFormat,
                           CallProfile profile, boolean thinkingDisabled) {

    public ModelRequest {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("scope is required");
        }
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        toolChoice = toolChoice == null ? ToolChoice.AUTO : toolChoice;
        responseFormat = responseFormat == null ? ResponseFormat.text() : responseFormat;
        profile = profile == null ? CallProfile.CHAT_STREAM : profile;
    }

    public static Builder builder(String scope) {
        return new Builder(scope);
    }

    public ModelRequest withEndpoint(ModelEndpointConfig value) {
        return new ModelRequest(scope, value, messages, tools, toolChoice, responseFormat, profile, thinkingDisabled);
    }

    public ModelRequest withThinkingDisabled() {
        return new ModelRequest(scope, endpoint, messages, tools, toolChoice, responseFormat, profile, true);
    }

    public static final class Builder {
        private final String scope;
        private ModelEndpointConfig endpoint;
        private final List<ChatMessage> messages = new ArrayList<>();
        private final List<ToolSpec> tools = new ArrayList<>();
        private ToolChoice toolChoice = ToolChoice.AUTO;
        private ResponseFormat responseFormat = ResponseFormat.text();
        private CallProfile profile = CallProfile.CHAT_STREAM;
        private boolean thinkingDisabled;

        private Builder(String scope) {
            this.scope = scope;
        }

        public Builder endpoint(ModelEndpointConfig value) {
            this.endpoint = value;
            return this;
        }

        public Builder message(ChatMessage value) {
            this.messages.add(value);
            return this;
        }

        public Builder messages(List<ChatMessage> values) {
            this.messages.addAll(values);
            return this;
        }

        public Builder tools(List<ToolSpec> values) {
            this.tools.addAll(values);
            return this;
        }

        public Builder toolChoice(ToolChoice value) {
            this.toolChoice = value;
            return this;
        }

        public Builder responseFormat(ResponseFormat value) {
            this.responseFormat = value;
            return this;
        }

        public Builder profile(CallProfile value) {
            this.profile = value;
            return this;
        }

        public Builder thinkingDisabled(boolean value) {
            this.thinkingDisabled = value;
            return this;
        }

        public ModelRequest build() {
            return new ModelRequest(scope, endpoint, messages, tools, toolChoice, responseFormat, profile,
                    thinkingDisabled);
        }
    }
}
