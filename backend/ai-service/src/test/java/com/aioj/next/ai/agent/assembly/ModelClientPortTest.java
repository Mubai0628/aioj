package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.runtime.api.AgentLoops;
import com.aioj.next.agent.harness.runtime.api.AgentScenario;
import com.aioj.next.agent.harness.runtime.api.CancellationToken;
import com.aioj.next.agent.harness.runtime.api.HarnessMessage;
import com.aioj.next.agent.harness.runtime.api.ModelCall;
import com.aioj.next.agent.harness.runtime.api.ModelCallException;
import com.aioj.next.agent.harness.runtime.api.ModelTool;
import com.aioj.next.agent.harness.runtime.api.ModelTurn;
import com.aioj.next.agent.harness.runtime.api.RunBudget;
import com.aioj.next.agent.harness.runtime.api.RunResult;
import com.aioj.next.agent.harness.runtime.api.RunSpec;
import com.aioj.next.agent.harness.tool.api.ExecutionClass;
import com.aioj.next.agent.harness.tool.api.FailurePolicy;
import com.aioj.next.agent.harness.tool.api.ToolAuditLevel;
import com.aioj.next.agent.harness.tool.api.ToolCallRequest;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.agent.harness.tool.api.ToolExecutionContext;
import com.aioj.next.agent.harness.tool.api.ToolOutcome;
import com.aioj.next.agent.harness.tool.api.ToolResult;
import com.aioj.next.agent.harness.tool.api.ToolRuntime;
import com.aioj.next.ai.agent.runtime.AgentExecutionMode;
import com.aioj.next.ai.model.api.CallOptions;
import com.aioj.next.ai.model.api.CallProfile;
import com.aioj.next.ai.model.api.ChatRole;
import com.aioj.next.ai.model.api.EmbeddingRequest;
import com.aioj.next.ai.model.api.EmbeddingResponse;
import com.aioj.next.ai.model.api.ModelClient;
import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ModelErrorKind;
import com.aioj.next.ai.model.api.ModelException;
import com.aioj.next.ai.model.api.ModelInfo;
import com.aioj.next.ai.model.api.ModelRequest;
import com.aioj.next.ai.model.api.ModelResponse;
import com.aioj.next.ai.model.api.ModelStreamListener;
import com.aioj.next.ai.model.api.ModelUsage;
import com.aioj.next.ai.model.api.ProviderCapabilities;
import com.aioj.next.ai.model.api.ProviderId;
import com.aioj.next.ai.model.api.ToolCall;
import com.aioj.next.ai.model.api.ToolChoice;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelClientPortTest {
    private static final ModelEndpointConfig DEEPSEEK = ModelEndpointConfig.builder(ProviderId.DEEPSEEK)
            .baseUrl("http://127.0.0.1:9/chat/completions").apiKey("test-key").model("deepseek-flash")
            .enabled(true).thinking(true, "high").build();
    private static final ProviderCapabilities DEEPSEEK_CAPS = new ProviderCapabilities(ProviderId.DEEPSEEK,
            "deepseek-flash", true, false, true, false, false, true, true, false, true, false, true);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FakeModelClient client = new FakeModelClient(DEEPSEEK, DEEPSEEK_CAPS);
    private final List<ModelResponse> observed = new ArrayList<>();

    @Test
    void convertsTheLoopConversationIntoOneRequestOnTheRunEndpoint() {
        client.reply(answer("ok"));
        ModelClientPort port = open(CallProfile.CHAT_STREAM, false);
        ToolCallRequest call = new ToolCallRequest("call-1", "context.search_exact", "{\"q\":\"xor\"}");

        port.complete(new ModelCall(List.of(
                HarnessMessage.system("rules"),
                HarnessMessage.user("question"),
                HarnessMessage.assistant("", "private plan", List.of(call)),
                HarnessMessage.toolResult("call-1", "{\"status\":\"SUCCESS\"}")),
                List.of(new ModelTool("context.search_exact", "Search", JSON.createObjectNode())),
                com.aioj.next.agent.harness.runtime.api.ToolChoice.REQUIRED, "CHAT_STREAM", null, null));

        ModelRequest request = client.onlyRequest();
        assertThat(request.scope()).isEqualTo("TEXT_GENERATION");
        assertThat(request.endpoint()).isSameAs(DEEPSEEK);
        assertThat(request.profile()).isEqualTo(CallProfile.CHAT_STREAM);
        assertThat(request.toolChoice()).isEqualTo(ToolChoice.REQUIRED);
        assertThat(request.messages()).extracting(message -> message.role())
                .containsExactly(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL);
        assertThat(request.messages().get(2).reasoningContent()).isEqualTo("private plan");
        assertThat(request.messages().get(2).toolCalls())
                .containsExactly(new ToolCall("call-1", "context.search_exact", "{\"q\":\"xor\"}"));
        assertThat(request.messages().get(3).toolCallId()).isEqualTo("call-1");
        assertThat(request.tools()).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("context.search_exact");
            assertThat(tool.description()).isEqualTo("Search");
        });
        assertThat(request.thinkingDisabled()).isFalse();
    }

    @Test
    void returnsTheProviderAnswerAsATurn() {
        client.reply(new ModelResponse("", "think", List.of(new ToolCall("c1", "problem.search", "{}")), "tool_calls",
                new ModelUsage(1200, 80, 1024, true), ProviderId.DEEPSEEK, "deepseek-flash"));

        ModelTurn turn = open(CallProfile.CHAT_STREAM, false).complete(call("CHAT_STREAM", null, null));

        assertThat(turn.reasoningContent()).isEqualTo("think");
        assertThat(turn.toolCalls()).containsExactly(new ToolCallRequest("c1", "problem.search", "{}"));
        assertThat(turn.finishReason()).isEqualTo("tool_calls");
        assertThat(turn.usage().inputTokens()).isEqualTo(1200);
        assertThat(turn.usage().outputTokens()).isEqualTo(80);
        assertThat(turn.usage().cacheHitTokens()).isEqualTo(1024);
        assertThat(turn.provider()).isEqualTo("deepseek");
        assertThat(turn.model()).isEqualTo("deepseek-flash");
        assertThat(turn.warnings()).isEmpty();
    }

    @Test
    void fallsBackToStandardToolsOnceWhenThinkingIsRefused() {
        client.reply(refused(ModelErrorKind.THINKING_UNAVAILABLE));
        client.reply(answer("first"));
        client.reply(answer("second"));
        ModelClientPort port = open(CallProfile.PROBLEM_DRAFT, true);
        assertThat(port.executionMode()).isEqualTo(AgentExecutionMode.THINKING);

        ModelTurn first = port.complete(call("PROBLEM_DRAFT", null, null));
        port.complete(call("PROBLEM_DRAFT", null, null));

        assertThat(first.content()).isEqualTo("first");
        assertThat(first.warnings()).containsExactly("STANDARD_TOOL_FALLBACK");
        assertThat(client.requests).extracting(ModelRequest::thinkingDisabled).containsExactly(false, true, true);
        assertThat(port.executionMode()).isEqualTo(AgentExecutionMode.STANDARD_TOOL_FALLBACK);
    }

    @Test
    void withoutFallbackARefusedThinkingCallFailsAsRejected() {
        ModelException refusal = refused(ModelErrorKind.THINKING_UNAVAILABLE);
        client.reply(refusal);
        ModelClientPort port = open(CallProfile.CHAT_STREAM, false);

        assertThatThrownBy(() -> port.complete(call("CHAT_STREAM", null, null)))
                .isInstanceOfSatisfying(ModelCallException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(ModelCallException.Kind.REJECTED));
        assertThat(port.lastFailure()).containsSame(refusal);
        assertThat(client.requests).hasSize(1);
    }

    @Test
    void providerErrorsMapToLoopKinds() {
        assertThat(ModelClientPort.kindOf(ModelErrorKind.TIMEOUT)).isEqualTo(ModelCallException.Kind.TIMEOUT);
        assertThat(ModelClientPort.kindOf(ModelErrorKind.RATE_LIMITED)).isEqualTo(ModelCallException.Kind.RATE_LIMITED);
        assertThat(ModelClientPort.kindOf(ModelErrorKind.QUOTA_EXHAUSTED))
                .isEqualTo(ModelCallException.Kind.RATE_LIMITED);
        assertThat(ModelClientPort.kindOf(ModelErrorKind.AUTHENTICATION))
                .isEqualTo(ModelCallException.Kind.AUTHENTICATION);
        assertThat(ModelClientPort.kindOf(ModelErrorKind.CANCELLED)).isEqualTo(ModelCallException.Kind.CANCELLED);
        assertThat(ModelClientPort.kindOf(ModelErrorKind.INVALID_RESPONSE))
                .isEqualTo(ModelCallException.Kind.INVALID_RESPONSE);
        assertThat(ModelClientPort.kindOf(ModelErrorKind.CONFIGURATION_INVALID))
                .isEqualTo(ModelCallException.Kind.REJECTED);
        for (ModelErrorKind kind : ModelErrorKind.values()) {
            assertThat(ModelClientPort.kindOf(kind)).as(kind.name()).isNotNull();
        }
    }

    @Test
    void theTimeLeftInTheRunBecomesTheDeadline() {
        client.reply(answer("a"));
        client.reply(answer("b"));
        ModelClientPort port = open(CallProfile.PROBLEM_DRAFT, true);
        Instant before = Instant.now();

        port.complete(call("PROBLEM_DRAFT", Duration.ofSeconds(30), null));
        port.complete(call("PROBLEM_DRAFT", null, null));

        assertThat(client.options.get(0).deadline()).isBetween(before.plusSeconds(29), Instant.now().plusSeconds(30));
        assertThat(client.options.get(1).deadline()).isNull();
        assertThat(client.options.get(0).feature()).isEqualTo("agent.problem_draft");
        assertThat(client.options.get(0).correlationId()).isEqualTo("turn-1");
    }

    @Test
    void cancellingTheRunCancelsTheCall() {
        client.reply(answer("a"));
        CancellationToken token = new CancellationToken();

        open(CallProfile.CHAT_STREAM, false).complete(call("CHAT_STREAM", null, token));
        assertThat(client.options.get(0).cancellation().isCancelled()).isFalse();
        token.cancel();

        assertThat(client.options.get(0).cancellation().isCancelled()).isTrue();
    }

    @Test
    void requiredToolChoiceFollowsTheProvider() {
        ProviderCapabilities k3 = new ProviderCapabilities(ProviderId.KIMI, "kimi-k3", true, false, true, true, true,
                true, true, false, false, true, true);
        ModelEndpointConfig kimi = ModelEndpointConfig.builder(ProviderId.KIMI).baseUrl("http://127.0.0.1:9/v1")
                .apiKey("test-key").model("kimi-k3").enabled(true).build();

        assertThat(open(CallProfile.CHAT_STREAM, false).supportsRequiredToolChoice()).isFalse();
        assertThat(ModelClientPort.open(new FakeModelClient(kimi, k3), "TEXT_GENERATION", CallProfile.CHAT_STREAM,
                options("chat_stream"), false, null).supportsRequiredToolChoice()).isTrue();
    }

    @Test
    void aFallbackRunOnANonThinkingEndpointStartsInStandardMode() {
        ModelEndpointConfig plain = ModelEndpointConfig.builder(ProviderId.DEEPSEEK)
                .baseUrl("http://127.0.0.1:9/chat/completions").apiKey("test-key").model("deepseek-flash")
                .enabled(true).thinking(false, null).build();
        FakeModelClient plainClient = new FakeModelClient(plain, DEEPSEEK_CAPS);
        plainClient.reply(answer("ok"));

        ModelClientPort port = ModelClientPort.open(plainClient, "PROBLEM_DRAFT", CallProfile.PROBLEM_DRAFT,
                options("problem_draft"), true, null);
        port.complete(call("PROBLEM_DRAFT", null, null));

        assertThat(port.executionMode()).isEqualTo(AgentExecutionMode.STANDARD_TOOL_FALLBACK);
        assertThat(plainClient.onlyRequest().thinkingDisabled()).isTrue();
    }

    @Test
    void switchingToStandardToolsTurnsThinkingOffForTheRestOfTheRun() {
        client.reply(answer("a"));
        client.reply(answer("b"));
        ModelClientPort port = open(CallProfile.PROBLEM_DRAFT, true);

        port.complete(call("PROBLEM_DRAFT", null, null));
        port.switchToStandardTools();
        port.complete(call("PROBLEM_DRAFT", null, null));

        assertThat(client.requests).extracting(ModelRequest::thinkingDisabled).containsExactly(false, true);
        assertThat(port.standardFallback()).isTrue();
    }

    @Test
    void everyResponseIsObservedAndAFailingObserverIsIgnored() {
        client.reply(answer("a"));
        client.reply(answer("b"));
        ModelClientPort observing = open(CallProfile.CHAT_STREAM, false);
        ModelClientPort failing = ModelClientPort.open(client, "TEXT_GENERATION", CallProfile.CHAT_STREAM,
                options("chat_stream"), false, response -> {
                    throw new IllegalStateException("observer down");
                });

        observing.complete(call("CHAT_STREAM", null, null));
        ModelTurn turn = failing.complete(call("CHAT_STREAM", null, null));

        assertThat(observed).extracting(ModelResponse::content).containsExactly("a");
        assertThat(turn.content()).isEqualTo("b");
    }

    /** The adapter under the real loop: reasoning stays in the run and is sent back with the tool result. */
    @Test
    void runsAToolLoopThroughTheHarness() {
        client.reply(new ModelResponse("", "plan the search", List.of(new ToolCall("c1", "context.search_exact",
                "{\"q\":\"xor\"}")), "tool_calls", new ModelUsage(100, 10, 0, true), ProviderId.DEEPSEEK,
                "deepseek-flash"));
        client.reply(new ModelResponse("final answer", null, List.of(), "stop", new ModelUsage(150, 20, 64, true),
                ProviderId.DEEPSEEK, "deepseek-flash"));
        ModelClientPort port = open(CallProfile.CHAT_STREAM, false);
        AgentScenario scenario = new AgentScenario("tutor", Set.of("AI_CHAT"), List.of(), null,
                RunBudget.builder(8, 6).build(), CallProfile.CHAT_STREAM.name(), CallProfile.RECOVERY_ANSWER.name());
        ToolExecutionContext context = new ToolExecutionContext("run-1", "7", "conv-1", "turn-1", "policy-1",
                Set.of("AI_CHAT"), Instant.now(), "turn-1", Map.of(), Map.of());

        RunResult result = AgentLoops.create(port, new OneToolRuntime()).run(
                RunSpec.builder("run-1", scenario, context).message(HarnessMessage.user("question")).build());

        assertThat(result.completed()).isTrue();
        assertThat(result.content()).isEqualTo("final answer");
        assertThat(result.toolCallCount()).isEqualTo(1);
        assertThat(result.usage().inputTokens()).isEqualTo(250);
        assertThat(result.usage().cacheHitTokens()).isEqualTo(64);
        ModelRequest second = client.requests.get(1);
        assertThat(second.messages().get(1).reasoningContent()).isEqualTo("plan the search");
        assertThat(second.messages().get(2).role()).isEqualTo(ChatRole.TOOL);
        assertThat(observed).hasSize(2);
    }

    private ModelClientPort open(CallProfile profile, boolean fallbackAllowed) {
        return ModelClientPort.open(client, "TEXT_GENERATION", profile,
                options(profile.name().toLowerCase(java.util.Locale.ROOT)), fallbackAllowed, observed::add);
    }

    private static CallOptions options(String profile) {
        return CallOptions.of("agent." + profile).withCorrelation("turn-1", 7L);
    }

    private static ModelCall call(String profile, Duration timeout, CancellationToken token) {
        return new ModelCall(List.of(HarnessMessage.user("question")), List.of(),
                com.aioj.next.agent.harness.runtime.api.ToolChoice.AUTO, profile, timeout, token);
    }

    private static ModelResponse answer(String content) {
        return new ModelResponse(content, null, List.of(), "stop", new ModelUsage(10, 5, 0, true), ProviderId.DEEPSEEK,
                "deepseek-flash");
    }

    private static ModelException refused(ModelErrorKind kind) {
        return new ModelException(kind, ProviderId.DEEPSEEK, "deepseek-flash", "AI provider refused the request");
    }

    /** Answers {@code complete} from a queue and records what it was asked. */
    private static final class FakeModelClient implements ModelClient {
        private final ModelEndpointConfig endpoint;
        private final ProviderCapabilities capabilities;
        private final Deque<Object> replies = new ArrayDeque<>();
        private final List<ModelRequest> requests = new ArrayList<>();
        private final List<CallOptions> options = new ArrayList<>();

        FakeModelClient(ModelEndpointConfig endpoint, ProviderCapabilities capabilities) {
            this.endpoint = endpoint;
            this.capabilities = capabilities;
        }

        void reply(Object reply) {
            replies.add(reply);
        }

        ModelRequest onlyRequest() {
            assertThat(requests).hasSize(1);
            return requests.get(0);
        }

        @Override
        public ModelEndpointConfig resolve(String scope) {
            return endpoint;
        }

        @Override
        public ProviderCapabilities capabilities(ModelEndpointConfig endpoint) {
            return capabilities;
        }

        @Override
        public ModelResponse complete(ModelRequest request, CallOptions callOptions) {
            requests.add(request);
            options.add(callOptions);
            Object next = replies.removeFirst();
            if (next instanceof ModelException ex) {
                throw ex;
            }
            return (ModelResponse) next;
        }

        @Override
        public ModelResponse stream(ModelRequest request, CallOptions callOptions, ModelStreamListener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EmbeddingResponse embed(EmbeddingRequest request, CallOptions callOptions) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ModelInfo> listModels(ModelEndpointConfig candidate, CallOptions callOptions) {
            throw new UnsupportedOperationException();
        }
    }

    /** Offers one read-only tool and answers every call with the same success payload. */
    private static final class OneToolRuntime implements ToolRuntime {
        private final ToolDescriptor descriptor = new ToolDescriptor("context.search_exact", "1", "Search",
                JSON.createObjectNode(), Set.of("AI_CHAT"), 1000, Duration.ofSeconds(5), "search",
                ExecutionClass.READ_ONLY, FailurePolicy.FAIL_OPEN, ToolAuditLevel.FULL);

        @Override
        public List<ToolDescriptor> visibleTools(Set<String> grantedScopes) {
            return grantedScopes.contains("AI_CHAT") ? List.of(descriptor) : List.of();
        }

        @Override
        public ToolOutcome execute(ToolExecutionContext context, int callSeq, ToolCallRequest call, Duration remaining) {
            return new ToolOutcome(call.callId(), call.toolName(), ToolResult.success("hit", List.of(), null, null),
                    "{\"status\":\"SUCCESS\",\"data\":\"hit\",\"instructionAllowed\":false}", "decision-1", 3, false);
        }

        @Override
        public void cancelRun(String runId) {
        }

        @Override
        public void close() {
        }
    }
}
