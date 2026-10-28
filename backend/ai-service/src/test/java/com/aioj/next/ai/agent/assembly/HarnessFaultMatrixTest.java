package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.context.api.ContextAssembler;
import com.aioj.next.agent.harness.context.api.ContextAssemblers;
import com.aioj.next.agent.harness.memory.api.MemoryPort;
import com.aioj.next.agent.harness.profile.api.ProfilePort;
import com.aioj.next.agent.harness.runtime.api.AgentLoops;
import com.aioj.next.agent.harness.runtime.api.AgentScenario;
import com.aioj.next.agent.harness.runtime.api.CompletionPolicy;
import com.aioj.next.agent.harness.runtime.api.ModelCall;
import com.aioj.next.agent.harness.runtime.api.ModelPort;
import com.aioj.next.agent.harness.runtime.api.ModelTurn;
import com.aioj.next.agent.harness.runtime.api.RunBudget;
import com.aioj.next.agent.harness.runtime.api.RunResult;
import com.aioj.next.agent.harness.runtime.api.RunSpec;
import com.aioj.next.agent.harness.runtime.api.RunStatus;
import com.aioj.next.agent.harness.runtime.api.TokenUsage;
import com.aioj.next.agent.harness.tool.api.AgentTool;
import com.aioj.next.agent.harness.tool.api.BulkheadLimits;
import com.aioj.next.agent.harness.tool.api.ToolAuditSink;
import com.aioj.next.agent.harness.tool.api.ToolCallRequest;
import com.aioj.next.agent.harness.tool.api.ToolCatalog;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.agent.harness.tool.api.ToolExecutionContext;
import com.aioj.next.agent.harness.tool.api.ToolResult;
import com.aioj.next.agent.harness.tool.api.ToolRuntime;
import com.aioj.next.agent.harness.tool.api.ToolRuntimes;
import com.aioj.next.agent.harness.sandbox.api.ExecutionFeedback;
import com.aioj.next.ai.agent.context.BootstrapContextBuilder;
import com.aioj.next.ai.agent.context.ContextBudgetAllocator;
import com.aioj.next.ai.agent.context.ContextManifestService;
import com.aioj.next.ai.agent.context.ContextSectionRenderer;
import com.aioj.next.ai.agent.context.TutorContextContributors;
import com.aioj.next.ai.agent.policy.GuardDecisionRecorder;
import com.aioj.next.ai.agent.policy.PolicySnapshotService;
import com.aioj.next.ai.agent.problem.tool.CodeRunCustomTool;
import com.aioj.next.ai.agent.problem.tool.TutorCodeExecutor;
import com.aioj.next.ai.agent.runtime.AgentRunStateMachine;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.AiConversationService;
import com.aioj.next.ai.domain.ProblemServiceClient;
import com.aioj.next.ai.persistence.mapper.AiTurnDigestMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan §6.3 fault injection, one row per Harness port a tutor turn uses: memory, profile, the
 * context contributors, a tool, and agent-executor behind the code tools. Each port is made to
 * throw and to overrun. The bootstrap, the Harness loop, the tool pipeline and the listener that
 * writes the manifest are the real ones; only the model is scripted. Every faulted turn still
 * answers, its manifest names the degraded part, and the same assembler and tool runtime then
 * serve a clean turn, so a fault leaves nothing broken behind it. The policy section is the one
 * part whose failure refuses the turn ({@link com.aioj.next.ai.agent.context.BootstrapContextFaultInjectionTest});
 * channel subscribers run after the turn and are covered by
 * {@link com.aioj.next.ai.agent.asyncjob.AgentAsyncJobWorkerTest}.
 */
class HarnessFaultMatrixTest {
    private static final String SELECTED = "#include <cstdio>\nint main(){int a,b;scanf(\"%d%d\",&a,&b);printf(\"%d\\n\",a+b);}";
    private static final String RUN_CUSTOM = "{\"source\":\"selection\",\"language\":\"cpp\",\"stdin\":\"1 2\\n\"}";
    private static final PolicySnapshotService.PolicySnapshot POLICY =
            new PolicySnapshotService.PolicySnapshot("ps-empty", null, List.of(), "{}", "", List.of());
    private static final AgentScenario SCENARIO = new AgentScenario("test", Set.of("AI_CHAT"), List.of(),
            CompletionPolicy.anyAnswer(), RunBudget.builder(4, 2).build(), "normal", "recovery");

    private final AiConversationService conversations = mock(AiConversationService.class);
    private final AiTurnDigestMapper digests = mock(AiTurnDigestMapper.class);
    private final TutorCodeExecutor executor = mock(TutorCodeExecutor.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ContextAssembler assembler = ContextAssemblers.create(2, 8);
    private final List<ToolRuntime> runtimes = new ArrayList<>();

    HarnessFaultMatrixTest() {
        when(conversations.messages(7L, "c1")).thenReturn(List.of());
        when(digests.selectLatestForConversation(eq(7L), eq("c1"), anyInt())).thenReturn(List.of());
    }

    @AfterEach
    void close() {
        runtimes.forEach(ToolRuntime::close);
        assembler.close();
    }

    @Test
    void memoryThatThrowsOrOverrunsCostsOnlyItsSection() {
        Turn failed = turn(memory(() -> {
            throw new IllegalStateException("memory store down");
        }), healthyProfile(), tools(), answer("先确定单调性。"));
        Turn slow = turn(memory(() -> {
            Thread.sleep(5_000);
            return List.of();
        }), healthyProfile(), tools(), answer("先确定单调性。"));

        failed.answered("先确定单调性。").lastManifestWarns("contributor_degraded:memory:error");
        slow.answered("先确定单调性。").lastManifestWarns("contributor_degraded:memory:timeout");
        turn(healthyMemory(), healthyProfile(), tools(), answer("ok")).answered("ok").lastManifestWarns();
    }

    @Test
    void aProfileThatThrowsOrOverrunsCostsOnlyItsSection() {
        Turn failed = turn(healthyMemory(), profile(() -> {
            throw new IllegalStateException("profile store down");
        }), tools(), answer("先确定单调性。"));
        Turn slow = turn(healthyMemory(), profile(() -> {
            Thread.sleep(5_000);
            return List.of();
        }), tools(), answer("先确定单调性。"));

        failed.answered("先确定单调性。").lastManifestWarns("contributor_degraded:profile:error");
        slow.answered("先确定单调性。").lastManifestWarns("contributor_degraded:profile:timeout");
        turn(healthyMemory(), healthyProfile(), tools(), answer("ok")).answered("ok").lastManifestWarns();
    }

    @Test
    void aContextContributorThatThrowsOrOverrunsCostsOnlyItsSection() {
        when(conversations.messages(7L, "c1")).thenThrow(new IllegalStateException("db down"));
        when(digests.selectLatestForConversation(eq(7L), eq("c1"), anyInt())).thenAnswer(invocation -> {
            Thread.sleep(5_000);
            return List.of();
        });

        turn(healthyMemory(), healthyProfile(), tools(), answer("先确定单调性。")).answered("先确定单调性。")
                .lastManifestWarns("contributor_degraded:recent_turns:error",
                        "contributor_degraded:conversation_focus:timeout");

        reset(conversations, digests);
        when(conversations.messages(7L, "c1")).thenReturn(List.of());
        when(digests.selectLatestForConversation(eq(7L), eq("c1"), anyInt())).thenReturn(List.of());
        turn(healthyMemory(), healthyProfile(), tools(), answer("ok")).answered("ok").lastManifestWarns();
    }

    @Test
    void aToolThatThrowsOrOverrunsGoesBackToTheModelAsData() {
        ToolRuntime tools = tools(
                fake("kb.broken", (context, input) -> {
                    throw new IllegalStateException("index down");
                }),
                fake("kb.slow", (context, input) -> {
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    return ToolResult.success("late", List.of(), null, null);
                }),
                fake("kb.healthy", (context, input) -> ToolResult.success("found", List.of(), null, null)));

        long started = System.nanoTime();
        Turn faulted = turn(healthyMemory(), healthyProfile(), tools,
                toolCalls(call("c1", "kb.broken", "{\"query\":\"a\"}"), call("c2", "kb.slow", "{\"query\":\"b\"}")),
                answer("没有检索结果也能先讲思路。"));

        faulted.answered("没有检索结果也能先讲思路。")
                .lastManifestWarns("tool_degraded:kb.broken:execution_error", "tool_degraded:kb.slow:timeout");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(3_000);
        assertThat(faulted.secondCallToolResults()).contains("EXECUTION_ERROR", "TIMEOUT").doesNotContain("index down");
        turn(healthyMemory(), healthyProfile(), tools, toolCalls(call("c3", "kb.healthy", "{\"query\":\"c\"}")),
                answer("ok")).answered("ok").lastManifestWarns();
    }

    @Test
    void anUnreachableAgentExecutorEndsTheCodeToolCallButNotTheTurn() throws IOException {
        AiProperties properties = new AiProperties();
        properties.setAgentExecutorUri("http://127.0.0.1:" + closedPort());
        ToolRuntime tools = tools(new BridgedTool(runCustom(new TutorCodeExecutor(properties))));

        Turn turn = turn(healthyMemory(), healthyProfile(), tools, toolCalls(call("c1", "code.run_custom", RUN_CUSTOM)),
                answer("执行器暂时不可用，先按样例手算。"));

        turn.answered("执行器暂时不可用，先按样例手算。")
                .lastManifestWarns("tool_degraded:code.run_custom:service_unavailable");
        assertThat(turn.secondCallToolResults()).contains("SERVICE_UNAVAILABLE", "EXECUTOR_UNAVAILABLE");
    }

    @Test
    void anAgentExecutorThatOverrunsOrThrowsEndsTheCallAndTheNextCallStillRuns() {
        // The code tools allow agent-executor 60 seconds; the same tool with a short timeout keeps the test quick.
        ToolRuntime tools = tools(shortTimeout(new BridgedTool(runCustom(executor)), Duration.ofMillis(300)));
        when(executor.run(anyString(), anyString(), anyList(), any(), any(), anyString())).thenAnswer(invocation -> {
            Thread.sleep(5_000);
            return ok();
        });
        Turn slow = turn(healthyMemory(), healthyProfile(), tools, toolCalls(call("c1", "code.run_custom", RUN_CUSTOM)),
                answer("先按样例手算。"));

        reset(executor);
        when(executor.run(anyString(), anyString(), anyList(), any(), any(), anyString()))
                .thenThrow(new IllegalStateException("executor client broke"));
        Turn broken = turn(healthyMemory(), healthyProfile(), tools,
                toolCalls(call("c2", "code.run_custom", RUN_CUSTOM)), answer("先按样例手算。"));

        reset(executor);
        when(executor.run(anyString(), anyString(), anyList(), any(), any(), anyString())).thenReturn(ok());
        Turn clean = turn(healthyMemory(), healthyProfile(), tools, toolCalls(call("c3", "code.run_custom", RUN_CUSTOM)),
                answer("输出是 3。"));

        slow.answered("先按样例手算。").lastManifestWarns("tool_degraded:code.run_custom:timeout");
        broken.answered("先按样例手算。").lastManifestWarns("tool_degraded:code.run_custom:execution_error");
        assertThat(broken.secondCallToolResults()).doesNotContain("executor client broke");
        clean.answered("输出是 3。").lastManifestWarns();
        assertThat(clean.secondCallToolResults()).contains("SUCCESS");
    }

    private Turn turn(MemoryPort memory, ProfilePort profile, ToolRuntime tools, ModelTurn... script) {
        AiProperties.AgentCore.Context settings = new AiProperties.AgentCore.Context();
        settings.setOptionalTimeoutMs(250);
        settings.setMemoryEnabled(true);
        settings.setProfileEnabled(true);
        var builder = new BootstrapContextBuilder(assembler,
                new TutorContextContributors(conversations, digests, mapper, settings, memory, profile),
                new ContextBudgetAllocator());
        var context = builder.build(7L, "c1", "这段代码为什么输出不对", POLICY, null, null, 6, 6000);
        ContextManifestService manifests = mock(ContextManifestService.class);
        var listener = new RunProgressListener(mock(AgentRunStateMachine.class), manifests, 42L, "t1", "fake",
                "ps-empty", context.sections(), context.warnings());
        var executionContext = new ToolExecutionContext("42", "7", "c1", "t1", "ps-empty", Set.of("AI_CHAT"),
                Instant.now(), "trace", Map.of("turn.selection", SELECTED), Map.of());
        var spec = RunSpec.builder("42", SCENARIO, executionContext)
                .messages(HarnessMessages.fromChat(new ContextSectionRenderer().render(context.sections())))
                .listener(listener).build();
        ScriptedModel model = new ScriptedModel(script);

        RunResult result = AgentLoops.create(model, tools).run(spec);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> warnings = ArgumentCaptor.forClass(List.class);
        verify(manifests, atLeastOnce()).record(eq("t1"), anyLong(), anyInt(), eq("fake"), anyString(), eq("ps-empty"),
                any(), anyString(), anyString(), isNull(), isNull(), warnings.capture());
        return new Turn(result, warnings.getAllValues(), model.calls);
    }

    private record Turn(RunResult result, List<List<String>> manifestWarnings, List<ModelCall> calls) {
        Turn answered(String content) {
            assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(result.content()).isEqualTo(content);
            return this;
        }

        Turn lastManifestWarns(String... expected) {
            assertThat(manifestWarnings).hasSize(calls.size());
            assertThat(manifestWarnings.get(manifestWarnings.size() - 1)).containsExactlyInAnyOrder(expected);
            return this;
        }

        String secondCallToolResults() {
            assertThat(calls).hasSizeGreaterThanOrEqualTo(2);
            StringBuilder results = new StringBuilder();
            calls.get(1).messages().stream()
                    .filter(message -> message.role() == com.aioj.next.agent.harness.runtime.api.HarnessMessage.Role.TOOL)
                    .forEach(message -> results.append(message.content()).append('\n'));
            return results.toString();
        }
    }

    private static final class ScriptedModel implements ModelPort {
        private final Deque<ModelTurn> turns;
        private final List<ModelCall> calls = new ArrayList<>();

        ScriptedModel(ModelTurn... turns) {
            this.turns = new ArrayDeque<>(List.of(turns));
        }

        @Override
        public synchronized ModelTurn complete(ModelCall call) {
            calls.add(call);
            return turns.removeFirst();
        }

        @Override
        public boolean supportsRequiredToolChoice() {
            return true;
        }
    }

    private ToolRuntime tools(AgentTool... tools) {
        ToolRuntime runtime = ToolRuntimes.create(ToolCatalog.of(List.of(tools)), List.of(), ToolAuditSink.NONE,
                BulkheadLimits.defaults());
        runtimes.add(runtime);
        return runtime;
    }

    private CodeRunCustomTool runCustom(TutorCodeExecutor codeExecutor) {
        return new CodeRunCustomTool(mock(ProblemServiceClient.class), mock(GuardDecisionRecorder.class), codeExecutor,
                mapper);
    }

    private static TutorCodeExecutor.Batch ok() {
        return new TutorCodeExecutor.Batch(List.of(new ExecutionFeedback(ExecutionFeedback.Status.OK, "3\n", "", "",
                12, 1024, false)), null);
    }

    private static AgentTool shortTimeout(AgentTool tool, Duration timeout) {
        ToolDescriptor original = tool.descriptor();
        ToolDescriptor descriptor = new ToolDescriptor(original.name(), original.version(), original.description(),
                original.inputSchema(), original.requiredScopes(), original.maxResultTokens(), timeout,
                original.budgetCategory(), original.executionClass(), original.failurePolicy(), original.auditLevel());
        return new AgentTool() {
            @Override
            public ToolDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ToolResult execute(ToolExecutionContext context, tools.jackson.databind.JsonNode input) {
                return tool.execute(context, input);
            }
        };
    }

    private static AgentTool fake(String name, java.util.function.BiFunction<ToolExecutionContext,
            tools.jackson.databind.JsonNode, ToolResult> behavior) {
        ToolDescriptor descriptor = new ToolDescriptor(name, "1.0.0", "fake tool", BridgedToolTest.schema(),
                Set.of("AI_CHAT"), 1000, Duration.ofMillis(300), "search",
                com.aioj.next.agent.harness.tool.api.ExecutionClass.READ_ONLY,
                com.aioj.next.agent.harness.tool.api.FailurePolicy.FAIL_OPEN,
                com.aioj.next.agent.harness.tool.api.ToolAuditLevel.NO_ARGUMENTS);
        return new AgentTool() {
            @Override
            public ToolDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ToolResult execute(ToolExecutionContext context, tools.jackson.databind.JsonNode input) {
                return behavior.apply(context, input);
            }
        };
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static ToolCallRequest call(String id, String name, String arguments) {
        return new ToolCallRequest(id, name, arguments);
    }

    private static ModelTurn toolCalls(ToolCallRequest... calls) {
        return new ModelTurn("", null, List.of(calls), "tool_calls", TokenUsage.ZERO, "fake", "fake", List.of());
    }

    private static ModelTurn answer(String content) {
        return new ModelTurn(content, null, List.of(), "stop", TokenUsage.ZERO, "fake", "fake", List.of());
    }

    @FunctionalInterface
    private interface Answer<T> {
        T get() throws Exception;
    }

    private static MemoryPort healthyMemory() {
        return memory(List::of);
    }

    private static ProfilePort healthyProfile() {
        return profile(List::of);
    }

    private static MemoryPort memory(Answer<List<MemoryPort.MemoryHit>> answer) {
        MemoryPort memory = mock(MemoryPort.class);
        when(memory.search(any())).thenAnswer(invocation -> answer.get());
        return memory;
    }

    private static ProfilePort profile(Answer<List<ProfilePort.ProfileSignal>> answer) {
        ProfilePort profile = mock(ProfilePort.class);
        when(profile.search(any())).thenAnswer(invocation -> answer.get());
        return profile;
    }
}
