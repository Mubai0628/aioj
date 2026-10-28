package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.context.api.ContextAssemblers;
import com.aioj.next.agent.harness.memory.api.MemoryPort;
import com.aioj.next.agent.harness.profile.api.ProfilePort;
import com.aioj.next.agent.harness.runtime.api.AgentLoops;
import com.aioj.next.agent.harness.runtime.api.AgentScenario;
import com.aioj.next.agent.harness.runtime.api.CompletionPolicy;
import com.aioj.next.agent.harness.runtime.api.ModelPort;
import com.aioj.next.agent.harness.runtime.api.ModelTurn;
import com.aioj.next.agent.harness.runtime.api.RunBudget;
import com.aioj.next.agent.harness.runtime.api.RunSpec;
import com.aioj.next.agent.harness.runtime.api.RunStatus;
import com.aioj.next.agent.harness.runtime.api.TokenUsage;
import com.aioj.next.agent.harness.tool.api.ToolExecutionContext;
import com.aioj.next.agent.harness.tool.api.ToolRuntime;
import com.aioj.next.ai.agent.context.BootstrapContextBuilder;
import com.aioj.next.ai.agent.context.ContextBudgetAllocator;
import com.aioj.next.ai.agent.context.ContextManifestService;
import com.aioj.next.ai.agent.context.ContextSectionRenderer;
import com.aioj.next.ai.agent.context.TutorContextContributors;
import com.aioj.next.ai.agent.policy.PolicySnapshotService;
import com.aioj.next.ai.agent.runtime.AgentRunStateMachine;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.AiConversationService;
import com.aioj.next.ai.persistence.mapper.AiTurnDigestMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Offline fault injection across bootstrap, the real Harness loop and the manifest listener. */
class BootstrapFaultEndToEndTest {
    @Test
    void memoryTimeoutAndProfileFailureStillCompleteAndRecordBothWarnings() {
        AiConversationService conversations = mock(AiConversationService.class);
        AiTurnDigestMapper digests = mock(AiTurnDigestMapper.class);
        MemoryPort memory = mock(MemoryPort.class);
        ProfilePort profile = mock(ProfilePort.class);
        when(conversations.messages(7L, "c1")).thenReturn(List.of());
        when(digests.selectLatestForConversation(eq(7L), eq("c1"), anyInt())).thenReturn(List.of());
        when(memory.search(any())).thenAnswer(invocation -> {
            Thread.sleep(5_000);
            return List.of();
        });
        when(profile.search(any())).thenThrow(new IllegalStateException("profile unavailable"));
        AiProperties.AgentCore.Context settings = new AiProperties.AgentCore.Context();
        settings.setOptionalTimeoutMs(250);
        settings.setMemoryEnabled(true);
        settings.setProfileEnabled(true);

        try (var assembler = ContextAssemblers.create(2, 8)) {
            var builder = new BootstrapContextBuilder(assembler,
                    new TutorContextContributors(conversations, digests, new ObjectMapper(), settings, memory, profile),
                    new ContextBudgetAllocator());
            var policy = new PolicySnapshotService.PolicySnapshot("ps-empty", null, List.of(), "{}", "", List.of());
            var context = builder.build(7L, "c1", "请解释二分查找", policy, null, null, 6, 6000);
            assertThat(context.warnings()).containsExactly("contributor_degraded:memory:timeout",
                    "contributor_degraded:profile:error");

            ContextManifestService manifests = mock(ContextManifestService.class);
            var listener = new RunProgressListener(mock(AgentRunStateMachine.class), manifests, 42L, "t1", "fake",
                    "ps-empty", context.sections(), context.warnings());
            ModelPort model = mock(ModelPort.class);
            when(model.complete(any())).thenReturn(new ModelTurn("先确定单调性。", null, List.of(), "stop",
                    TokenUsage.ZERO, "fake", "fake", List.of()));
            ToolRuntime tools = mock(ToolRuntime.class);
            when(tools.visibleTools(any())).thenReturn(List.of());
            var scenario = new AgentScenario("test", Set.of("AI_CHAT"), List.of(), CompletionPolicy.anyAnswer(),
                    RunBudget.builder(2, 0).build(), "normal", "recovery");
            var executionContext = new ToolExecutionContext("42", "7", "c1", "t1", "ps-empty", Set.of("AI_CHAT"),
                    Instant.now(), "trace", Map.of(), Map.of());
            var spec = RunSpec.builder("42", scenario, executionContext)
                    .messages(HarnessMessages.fromChat(new ContextSectionRenderer().render(context.sections())))
                    .listener(listener).build();

            var result = AgentLoops.create(model, tools).run(spec);
            assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(result.content()).isEqualTo("先确定单调性。");
            verify(model).complete(any());
            verify(manifests).record(eq("t1"), eq(42L), eq(1), eq("fake"), anyString(), eq("ps-empty"),
                    eq(context.sections()), anyString(), anyString(), isNull(), isNull(), eq(context.warnings()));
        }
    }
}
