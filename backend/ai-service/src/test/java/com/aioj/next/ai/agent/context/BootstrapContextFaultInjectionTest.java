package com.aioj.next.ai.agent.context;

import com.aioj.next.agent.harness.context.api.ContextAssemblers;
import com.aioj.next.agent.harness.context.api.ContextPolicyException;
import com.aioj.next.ai.agent.guard.GuardVerdict;
import com.aioj.next.ai.agent.policy.PolicySnapshotService;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.AiConversationService;
import com.aioj.next.ai.persistence.entity.AiTurnDigestEntity;
import com.aioj.next.ai.persistence.mapper.AiTurnDigestMapper;
import com.aioj.next.contract.ai.AiChatMessageResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan §6.3 / A3.2 fault injection for the bootstrap: an optional contributor that fails or
 * overruns costs only its own section and is listed in the warnings the manifest records; a
 * policy contributor that fails stops the turn.
 */
class BootstrapContextFaultInjectionTest {
    private final AiConversationService conversationService = mock(AiConversationService.class);
    private final AiTurnDigestMapper digestMapper = mock(AiTurnDigestMapper.class);

    @Test
    void aFailedRecentTurnsReadIsSkippedAndRecorded() {
        when(conversationService.messages(7L, "c1")).thenThrow(new IllegalStateException("db down"));
        when(digestMapper.selectLatestForConversation(eq(7L), eq("c1"), anyInt())).thenReturn(List.of(digest()));

        BootstrapContextBuilder.BootstrapContext context = builder(settings(3_000)).build(7L, "c1", "继续",
                emptyPolicy(), null, null, 6, 6000);

        assertThat(context.warnings()).containsExactly("contributor_degraded:recent_turns:error");
        assertThat(context.sections()).extracting(ContextSection::type).containsExactly(
                ContextSectionType.SYSTEM_POLICY, ContextSectionType.CONVERSATION_FOCUS,
                ContextSectionType.CURRENT_USER_REQUEST);
    }

    @Test
    void aSlowDigestReadTimesOutWithoutHoldingTheTurn() {
        when(conversationService.messages(7L, "c1")).thenReturn(List.of(message(1L, "user", "上一轮"),
                message(2L, "assistant", "上一轮的回答")));
        when(digestMapper.selectLatestForConversation(eq(7L), eq("c1"), anyInt())).thenAnswer(invocation -> {
            Thread.sleep(5_000);
            return List.of(digest());
        });

        long started = System.nanoTime();
        BootstrapContextBuilder.BootstrapContext context = builder(settings(150)).build(7L, "c1", "继续",
                emptyPolicy(), null, null, 6, 6000);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(context.warnings()).containsExactly("contributor_degraded:conversation_focus:timeout");
        assertThat(context.sections()).extracting(ContextSection::type).containsExactly(
                ContextSectionType.SYSTEM_POLICY, ContextSectionType.RECENT_TURNS,
                ContextSectionType.CURRENT_USER_REQUEST);
    }

    @Test
    void aBrokenPolicySectionStopsTheTurn() {
        GuardVerdict broken = mock(GuardVerdict.class);
        when(broken.hasMatches()).thenThrow(new IllegalStateException("verdict unreadable"));
        when(conversationService.messages(7L, "c1")).thenReturn(List.of());

        assertThatThrownBy(() -> builder(settings(3_000)).build(7L, "c1", "题面粘贴", emptyPolicy(), broken, null, 6, 6000))
                .isInstanceOfSatisfying(ContextPolicyException.class, ex -> {
                    assertThat(ex.contributor()).isEqualTo("contest_guard_match");
                    assertThat(ex.reason()).isEqualTo("error");
                });
    }

    private BootstrapContextBuilder builder(AiProperties.AgentCore.Context settings) {
        return new BootstrapContextBuilder(ContextAssemblers.create(2, 8),
                new TutorContextContributors(conversationService, digestMapper, new ObjectMapper(), settings),
                new ContextBudgetAllocator());
    }

    private static AiProperties.AgentCore.Context settings(long optionalTimeoutMs) {
        AiProperties.AgentCore.Context settings = new AiProperties.AgentCore.Context();
        settings.setOptionalTimeoutMs(optionalTimeoutMs);
        return settings;
    }

    private static PolicySnapshotService.PolicySnapshot emptyPolicy() {
        return new PolicySnapshotService.PolicySnapshot("ps-empty", null, List.of(), "{}", "", List.of());
    }

    private static AiChatMessageResponse message(Long id, String role, String content) {
        return new AiChatMessageResponse(id, "c1", null, null, role, content, null, "COMPLETED", null,
                Instant.parse("2026-10-27T00:00:00Z"), Instant.parse("2026-10-27T00:00:01Z"));
    }

    private static AiTurnDigestEntity digest() {
        AiTurnDigestEntity digest = new AiTurnDigestEntity();
        digest.setTurnId("t-1");
        digest.setSummary("较早的摘要");
        digest.setStructuredDigest("{\"openTasks\":[]}");
        return digest;
    }
}
