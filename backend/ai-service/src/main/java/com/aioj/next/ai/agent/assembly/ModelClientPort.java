package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.runtime.api.HarnessMessage;
import com.aioj.next.agent.harness.runtime.api.ModelCall;
import com.aioj.next.agent.harness.runtime.api.ModelCallException;
import com.aioj.next.agent.harness.runtime.api.ModelPort;
import com.aioj.next.agent.harness.runtime.api.ModelTool;
import com.aioj.next.agent.harness.runtime.api.ModelTurn;
import com.aioj.next.agent.harness.runtime.api.TokenUsage;
import com.aioj.next.agent.harness.tool.api.ToolCallRequest;
import com.aioj.next.ai.agent.runtime.AgentExecutionMode;
import com.aioj.next.ai.model.api.CallOptions;
import com.aioj.next.ai.model.api.CallProfile;
import com.aioj.next.ai.model.api.CancellationSignal;
import com.aioj.next.ai.model.api.ChatMessage;
import com.aioj.next.ai.model.api.ModelClient;
import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ModelErrorKind;
import com.aioj.next.ai.model.api.ModelException;
import com.aioj.next.ai.model.api.ModelRequest;
import com.aioj.next.ai.model.api.ModelResponse;
import com.aioj.next.ai.model.api.ProviderCapabilities;
import com.aioj.next.ai.model.api.ToolCall;
import com.aioj.next.ai.model.api.ToolChoice;
import com.aioj.next.ai.model.api.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One run's {@link ModelPort} over {@link ModelClient} (plan §3.3, A2.2). Every call of
 * the run goes to the endpoint resolved when the run opens it, and the model state of the
 * run lives here instead of in the domain-free loop:
 * <ul>
 *   <li>the standard-tool fallback: once the provider refuses thinking, or a stateful
 *       workflow asks for it, the rest of the run is called without thinking;</li>
 *   <li>the original {@link ModelException} of a failed call, so the caller can report
 *       it exactly as before;</li>
 *   <li>every provider response, for usage observation.</li>
 * </ul>
 * A call's profile is a {@link CallProfile} name. The time left in the run becomes the
 * call's deadline, and cancelling the run closes the connection.
 */
public final class ModelClientPort implements ModelPort {
    private static final Logger log = LoggerFactory.getLogger(ModelClientPort.class);
    static final String STANDARD_TOOL_FALLBACK = AgentExecutionMode.STANDARD_TOOL_FALLBACK.name();

    private final ModelClient modelClient;
    private final ModelEndpointConfig endpoint;
    private final ProviderCapabilities capabilities;
    private final String scope;
    private final CallOptions options;
    private final boolean fallbackAllowed;
    private final boolean thinksByDefault;
    private final Consumer<ModelResponse> responseObserver;
    private volatile boolean standardFallback;
    private volatile ModelException lastFailure;

    private ModelClientPort(ModelClient modelClient, ModelEndpointConfig endpoint, String scope, CallProfile profile,
                            CallOptions options, boolean fallbackAllowed, Consumer<ModelResponse> responseObserver) {
        this.modelClient = modelClient;
        this.endpoint = endpoint;
        this.capabilities = modelClient.capabilities(endpoint);
        this.scope = scope;
        this.options = options;
        this.fallbackAllowed = fallbackAllowed;
        this.thinksByDefault = capabilities.thinks(endpoint, profile, false);
        this.responseObserver = responseObserver == null ? response -> {
        } : responseObserver;
        // A workflow that may fall back but whose endpoint does not think starts there.
        this.standardFallback = fallbackAllowed && !thinksByDefault;
    }

    /**
     * Resolves the scope's endpoint once for the whole run.
     *
     * @param profile          the run's main profile; it decides the starting execution mode
     * @param fallbackAllowed  whether a refused thinking request may fall back to standard tools
     * @param responseObserver sees every provider response; it must not affect the run
     */
    public static ModelClientPort open(ModelClient modelClient, String scope, CallProfile profile, CallOptions options,
                                       boolean fallbackAllowed, Consumer<ModelResponse> responseObserver) {
        return new ModelClientPort(modelClient, modelClient.resolve(scope), scope, profile, options, fallbackAllowed,
                responseObserver);
    }

    @Override
    public ModelTurn complete(ModelCall call) {
        ModelRequest request = ModelRequest.builder(scope).endpoint(endpoint).messages(chatMessages(call.messages()))
                .tools(toolSpecs(call.tools())).toolChoice(toolChoice(call)).profile(CallProfile.valueOf(call.profile()))
                .build();
        CancellationSignal signal = new CancellationSignal();
        call.cancellation().onCancel(signal::cancel);
        CallOptions callOptions = options.withCancellation(signal);
        if (call.timeout() != null) {
            callOptions = callOptions.withTimeout(call.timeout());
        }
        List<String> warnings = new ArrayList<>();
        ModelResponse response;
        try {
            response = invoke(request, callOptions, warnings);
        } catch (ModelException ex) {
            lastFailure = ex;
            throw new ModelCallException(kindOf(ex.kind()), ex.getMessage());
        }
        observe(response);
        return turn(response, warnings);
    }

    @Override
    public boolean supportsRequiredToolChoice() {
        return capabilities.toolChoiceRequiredNative();
    }

    /** Calls the rest of the run without thinking, as a stateful workflow's recovery asks. */
    public void switchToStandardTools() {
        standardFallback = true;
    }

    public boolean standardFallback() {
        return standardFallback;
    }

    public AgentExecutionMode executionMode() {
        if (standardFallback) {
            return AgentExecutionMode.STANDARD_TOOL_FALLBACK;
        }
        return thinksByDefault ? AgentExecutionMode.THINKING : AgentExecutionMode.STANDARD;
    }

    public ModelEndpointConfig endpoint() {
        return endpoint;
    }

    /** The provider's own error of the last failed call, safe to report to the caller. */
    public Optional<ModelException> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    private ModelResponse invoke(ModelRequest request, CallOptions callOptions, List<String> warnings) {
        if (standardFallback) {
            return modelClient.complete(request.withThinkingDisabled(), callOptions);
        }
        try {
            return modelClient.complete(request, callOptions);
        } catch (ModelException ex) {
            if (ex.kind() != ModelErrorKind.THINKING_UNAVAILABLE || !fallbackAllowed) {
                throw ex;
            }
            // Exactly one controlled downgrade; the provider body and reasoning are neither logged nor kept.
            standardFallback = true;
            warnings.add(STANDARD_TOOL_FALLBACK);
            return modelClient.complete(request.withThinkingDisabled(), callOptions);
        }
    }

    private void observe(ModelResponse response) {
        try {
            responseObserver.accept(response);
        } catch (RuntimeException ex) {
            // Usage observation must never change the run.
            log.warn("model response observer failed scope={} error={}", scope, ex.toString());
        }
    }

    private static ToolChoice toolChoice(ModelCall call) {
        return call.toolChoice() == com.aioj.next.agent.harness.runtime.api.ToolChoice.REQUIRED
                ? ToolChoice.REQUIRED : ToolChoice.AUTO;
    }

    private static List<ChatMessage> chatMessages(List<HarnessMessage> messages) {
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

    private static List<ToolSpec> toolSpecs(List<ModelTool> tools) {
        return tools.stream().map(tool -> new ToolSpec(tool.name(), tool.description(), tool.inputSchema())).toList();
    }

    private static ModelTurn turn(ModelResponse response, List<String> warnings) {
        List<ToolCallRequest> calls = response.toolCalls().stream()
                .map(call -> new ToolCallRequest(call.id(), call.name(), call.argumentsJson()))
                .toList();
        TokenUsage usage = new TokenUsage(response.usage().promptTokens(), response.usage().completionTokens(),
                response.usage().cacheHitTokens());
        return new ModelTurn(response.content(), response.reasoningContent(), calls, response.finishReason(), usage,
                response.provider() == null ? null : response.provider().configName(), response.model(), warnings);
    }

    static ModelCallException.Kind kindOf(ModelErrorKind kind) {
        if (kind == null) {
            return ModelCallException.Kind.UNAVAILABLE;
        }
        return switch (kind) {
            case TIMEOUT -> ModelCallException.Kind.TIMEOUT;
            case RATE_LIMITED, QUOTA_EXHAUSTED -> ModelCallException.Kind.RATE_LIMITED;
            case UNAVAILABLE -> ModelCallException.Kind.UNAVAILABLE;
            case AUTHENTICATION -> ModelCallException.Kind.AUTHENTICATION;
            case INVALID_RESPONSE -> ModelCallException.Kind.INVALID_RESPONSE;
            case CANCELLED -> ModelCallException.Kind.CANCELLED;
            case CONFIGURATION_INVALID, CAPABILITY_UNSUPPORTED, THINKING_UNAVAILABLE, INVALID_REQUEST ->
                    ModelCallException.Kind.REJECTED;
        };
    }
}
