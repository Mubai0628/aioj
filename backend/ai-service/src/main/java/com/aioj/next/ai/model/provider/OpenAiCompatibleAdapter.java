package com.aioj.next.ai.model.provider;

import com.aioj.next.ai.model.api.ChatMessage;
import com.aioj.next.ai.model.api.ChatRole;
import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ModelErrorKind;
import com.aioj.next.ai.model.api.ModelException;
import com.aioj.next.ai.model.api.ModelRequest;
import com.aioj.next.ai.model.api.ModelResponse;
import com.aioj.next.ai.model.api.ProviderCapabilities;
import com.aioj.next.ai.model.api.ResponseFormat;
import com.aioj.next.ai.model.api.ToolCall;
import com.aioj.next.ai.model.api.ToolChoice;
import com.aioj.next.ai.model.api.ToolSpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The OpenAI-compatible chat dialect both providers speak. Subclasses only decide
 * thinking controls, temperature, token limits, tool_choice, per-function flags,
 * stream options and provider-specific error refinements.
 */
public abstract class OpenAiCompatibleAdapter implements ChatAdapter {
    protected static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Override
    public URI chatUri(ModelEndpointConfig endpoint) {
        return ProviderErrors.endpointUri(endpoint.baseUrl(), id(), endpoint.model());
    }

    @Override
    public ObjectNode chatBody(ModelEndpointConfig endpoint, CallSettings settings, ModelRequest request,
                               boolean stream) {
        ObjectNode body = NODES.objectNode();
        body.put("model", endpoint.model());
        body.set("messages", messages(request.messages()));
        body.put("stream", stream);
        if (stream) {
            streamOptions(body);
        }
        maxTokens(body, endpoint, settings);
        temperature(body, endpoint, settings);
        if (!request.tools().isEmpty()) {
            body.set("tools", tools(request.tools()));
            toolChoice(body, endpoint, request.toolChoice());
        }
        responseFormat(body, settings.responseFormat(), capabilities(endpoint.model()), endpoint);
        thinking(body, endpoint, settings);
        return body;
    }

    @Override
    public ModelResponse parseChat(String body, ModelEndpointConfig endpoint) {
        JsonNode root = ProviderErrors.readJson(body, id(), endpoint.model());
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw ProviderErrors.invalidResponse(id(), endpoint.model());
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");
        String reasoning = message.path("reasoning_content").isNull() || message.path("reasoning_content").isMissingNode()
                ? null
                : ProviderErrors.text(message.path("reasoning_content"));
        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode function = call.path("function");
            JsonNode arguments = function.path("arguments");
            toolCalls.add(new ToolCall(
                    call.path("id").asString(""),
                    ToolNameCodec.toInternal(function.path("name").asString("")),
                    arguments.isString() ? arguments.asString("") : arguments.isMissingNode() ? "{}" : arguments.toString()));
        }
        return new ModelResponse(ProviderErrors.text(message.path("content")), reasoning, toolCalls,
                choice.path("finish_reason").asString(""), ProviderErrors.usage(root.path("usage")), id(),
                endpoint.model());
    }

    @Override
    public void onStreamEvent(String data, StreamAccumulator accumulator) {
        JsonNode chunk = ProviderErrors.readJson(data, id(), null);
        accumulator.usage(ProviderErrors.usage(chunk.path("usage")));
        JsonNode choices = chunk.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return;
        }
        JsonNode choice = choices.get(0);
        JsonNode delta = choice.path("delta");
        accumulator.reasoning(ProviderErrors.stringOrNull(delta.path("reasoning_content")));
        accumulator.text(ProviderErrors.stringOrNull(delta.path("content")));
        for (JsonNode call : delta.path("tool_calls")) {
            JsonNode function = call.path("function");
            accumulator.toolCall(call.path("index").asInt(0), ProviderErrors.stringOrNull(call.path("id")),
                    ProviderErrors.stringOrNull(function.path("name")),
                    ProviderErrors.stringOrNull(function.path("arguments")));
        }
        accumulator.finish(ProviderErrors.stringOrNull(choice.path("finish_reason")));
        // Some OpenAI-compatible servers put the final usage inside the choice.
        accumulator.usage(ProviderErrors.usage(choice.path("usage")));
    }

    @Override
    public ModelException classify(int status, String body, Duration retryAfter, ModelEndpointConfig endpoint,
                                   CallSettings settings) {
        String errorType = ProviderErrors.errorType(body);
        ModelException refined = refine(status, errorType, body, retryAfter, endpoint, settings);
        return refined != null
                ? refined
                : ProviderErrors.httpFailure(id(), endpoint.model(), status, errorType, retryAfter);
    }

    /** Provider-specific classification ahead of the default mapping; {@code null} keeps the default. */
    protected ModelException refine(int status, String errorType, String body, Duration retryAfter,
                                    ModelEndpointConfig endpoint, CallSettings settings) {
        return null;
    }

    protected void streamOptions(ObjectNode body) {
    }

    protected void maxTokens(ObjectNode body, ModelEndpointConfig endpoint, CallSettings settings) {
        body.put("max_tokens", settings.maxTokens());
    }

    protected abstract void temperature(ObjectNode body, ModelEndpointConfig endpoint, CallSettings settings);

    protected abstract void thinking(ObjectNode body, ModelEndpointConfig endpoint, CallSettings settings);

    /** REQUIRED reaches here only where the capabilities allow it; the client checks first. */
    protected void toolChoice(ObjectNode body, ModelEndpointConfig endpoint, ToolChoice choice) {
        if (choice == ToolChoice.REQUIRED) {
            if (!capabilities(endpoint.model()).toolChoiceRequiredNative()) {
                throw requiredUnsupported(endpoint);
            }
            body.put("tool_choice", "required");
            return;
        }
        body.put("tool_choice", "auto");
    }

    protected void functionFlags(ObjectNode function, ToolSpec spec) {
    }

    protected ModelException requiredUnsupported(ModelEndpointConfig endpoint) {
        return new ModelException(ModelErrorKind.CAPABILITY_UNSUPPORTED, id(), endpoint.model(),
                "AI model " + endpoint.model() + " of " + id().configName()
                        + " has no native tool_choice=required; the Agent loop must simulate it");
    }

    private void responseFormat(ObjectNode body, ResponseFormat format, ProviderCapabilities capabilities,
                                ModelEndpointConfig endpoint) {
        if (!format.isJson()) {
            return;
        }
        if (format.type() == ResponseFormat.Type.JSON_SCHEMA && capabilities.jsonSchema()) {
            ObjectNode schema = body.putObject("response_format").put("type", "json_schema")
                    .putObject("json_schema");
            schema.put("name", format.schemaName());
            schema.put("strict", true);
            schema.set("schema", format.schema());
            return;
        }
        if (!capabilities.jsonObject()) {
            throw new ModelException(ModelErrorKind.CAPABILITY_UNSUPPORTED, id(), endpoint.model(),
                    "AI model " + endpoint.model() + " cannot be asked for JSON output");
        }
        body.putObject("response_format").put("type", "json_object");
    }

    private ArrayNode messages(List<ChatMessage> messages) {
        ArrayNode array = NODES.arrayNode();
        for (ChatMessage message : messages) {
            ObjectNode node = array.addObject();
            node.put("role", message.role().wireName());
            node.put("content", message.content() == null ? "" : message.content());
            if (message.role() == ChatRole.ASSISTANT && message.reasoningContent() != null) {
                // Every assistant tool call goes back with its full reasoning (appendix G.4 item 5):
                // DeepSeek answers HTTP 400 without it, Kimi's thinking models document the same.
                node.put("reasoning_content", message.reasoningContent());
            }
            if (!message.toolCalls().isEmpty()) {
                ArrayNode calls = node.putArray("tool_calls");
                for (ToolCall call : message.toolCalls()) {
                    ObjectNode wire = calls.addObject();
                    wire.put("id", call.id());
                    wire.put("type", "function");
                    ObjectNode function = wire.putObject("function");
                    function.put("name", ToolNameCodec.toWire(call.name()));
                    function.put("arguments", call.argumentsJson() == null ? "{}" : call.argumentsJson());
                }
            }
            if (message.role() == ChatRole.TOOL) {
                node.put("tool_call_id", message.toolCallId());
            }
        }
        return array;
    }

    private ArrayNode tools(List<ToolSpec> tools) {
        ArrayNode array = NODES.arrayNode();
        for (ToolSpec spec : tools) {
            ObjectNode tool = array.addObject();
            tool.put("type", "function");
            ObjectNode function = tool.putObject("function");
            function.put("name", ToolNameCodec.toWire(spec.name()));
            function.put("description", spec.description());
            function.set("parameters", spec.parameters());
            functionFlags(function, spec);
        }
        return array;
    }
}
