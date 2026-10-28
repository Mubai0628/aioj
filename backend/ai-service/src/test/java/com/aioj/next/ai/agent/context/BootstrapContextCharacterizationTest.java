package com.aioj.next.ai.agent.context;

import com.aioj.next.ai.agent.guard.GuardVerdict;
import com.aioj.next.ai.agent.policy.GuardDecisionRecorder.MatchedProblemRef;
import com.aioj.next.ai.agent.policy.PolicySnapshotService;
import com.aioj.next.ai.domain.AiConversationService;
import com.aioj.next.ai.model.api.ChatMessage;
import com.aioj.next.ai.persistence.entity.AiTurnDigestEntity;
import com.aioj.next.ai.persistence.mapper.AiTurnDigestMapper;
import com.aioj.next.contract.ai.AiChatMessageResponse;
import com.aioj.next.contract.ai.AiChatRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan T1.6: locks the bootstrap the model sees today, before the builder is
 * split into context contributors. For four kinds of turn it pins every section
 * (order, type, priority, atomicity, token estimate, trust, text), the budget
 * warnings and the messages the renderer sends to the model. The split must
 * leave all of it byte-for-byte unchanged. To re-record after an intended
 * change, run with {@code CHARACTERIZATION_RECORD=true} and review the diff.
 */
class BootstrapContextCharacterizationTest {
    private static final String SNAPSHOT = "characterization/bootstrap-context.txt";

    private final AiConversationService conversationService = mock(AiConversationService.class);
    private final AiTurnDigestMapper digestMapper = mock(AiTurnDigestMapper.class);
    private final BootstrapContextBuilder builder = new BootstrapContextBuilder(
            conversationService, new ContextBudgetAllocator(), digestMapper, new ObjectMapper());
    private final ContextSectionRenderer renderer = new ContextSectionRenderer();

    @Test
    void fourKindsOfTurnKeepTheirBootstrap() throws IOException {
        String actual = "=== practice turn with history and a digest\n" + render(practiceTurn())
                + "=== contest turn with a policy snapshot and a message-layer match\n" + render(contestTurn())
                + "=== entry and selection context, no history\n" + render(entryAndSelectionTurn())
                + "=== long history over a small budget\n" + render(trimmedHistoryTurn());
        if ("true".equals(System.getenv("CHARACTERIZATION_RECORD"))) {
            Path target = Path.of("src/test/resources", SNAPSHOT);
            Files.createDirectories(target.getParent());
            Files.writeString(target, actual, StandardCharsets.UTF_8);
        }
        try (InputStream expected = getClass().getClassLoader().getResourceAsStream(SNAPSHOT)) {
            assertThat(expected).as("snapshot " + SNAPSHOT + " (record it with CHARACTERIZATION_RECORD=true)").isNotNull();
            // A checkout with autocrlf may turn the snapshot's line ends into CRLF; the text itself must match.
            String snapshot = new String(expected.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertThat(actual).isEqualTo(snapshot);
        }
    }

    private BootstrapContextBuilder.BootstrapContext practiceTurn() {
        when(conversationService.messages(7L, "c1")).thenReturn(List.of(
                message(1L, "user", "第一题怎么解"),
                message(2L, "assistant", "先看约束"),
                message(3L, "user", "那二分的边界呢"),
                message(4L, "assistant", "左闭右开更不容易错"),
                message(5L, "system", "not a turn"),
                failedMessage(6L, "user", "这一条没完成")));
        when(digestMapper.selectLatestForConversation(eq(7L), eq("c1"), anyInt())).thenReturn(List.of(
                digest("t-2", "用户追问二分边界", "{\"openTasks\":[\"复习二分边界\",\"写一遍模板\"]}"),
                digest("t-1", "用户询问第一题二分思路", "{\"openTasks\":[\"复习二分边界\"]}")));
        return builder.build(7L, "c1", "继续", emptyPolicy(), null, null, 6, 6000);
    }

    private BootstrapContextBuilder.BootstrapContext contestTurn() {
        when(conversationService.messages(7L, "c1")).thenReturn(List.of());
        when(digestMapper.selectLatestForConversation(eq(7L), eq("c1"), anyInt())).thenReturn(List.of());
        GuardVerdict verdict = GuardVerdict.constrain(List.of(
                new MatchedProblemRef(1002L, 5501L, 7701L, 99001L, "PRIVATE", "DEFAULT")), 0.9);
        PolicySnapshotService.PolicySnapshot snapshot = new PolicySnapshotService.PolicySnapshot(
                "ps-contest", null, List.of(), "{}", "比赛进行中：只给思路，不给代码", List.of());
        return builder.build(7L, "c1", "题面粘贴", snapshot, verdict, null, 6, 6000);
    }

    private BootstrapContextBuilder.BootstrapContext entryAndSelectionTurn() {
        when(conversationService.messages(7L, "c1")).thenReturn(List.of());
        when(digestMapper.selectLatestForConversation(eq(7L), eq("c1"), anyInt())).thenReturn(List.of());
        BootstrapContextBuilder.EntryContext entry = new BootstrapContextBuilder.EntryContext(
                42L,
                new AiChatRequest.ContestContext(8L, 9L, 10L),
                new AiChatRequest.SubmissionContext(77L, "debug", true, null),
                new AiChatRequest.SelectionContext("sel-1", "c1", "problem_statement", null, null,
                        "选中的题面片段", "**选中的** Markdown", null, null, null, null, "explain"));
        return builder.build(7L, "c1", "这题怎么入手", emptyPolicy(), null, entry, 6, 6000);
    }

    private BootstrapContextBuilder.BootstrapContext trimmedHistoryTurn() {
        when(conversationService.messages(7L, "c1")).thenReturn(List.of(
                message(1L, "user", "u".repeat(80)),
                message(2L, "assistant", "a".repeat(80)),
                message(3L, "user", "v".repeat(80)),
                message(4L, "assistant", "b".repeat(80))));
        when(digestMapper.selectLatestForConversation(eq(7L), eq("c1"), anyInt())).thenReturn(List.of(
                digest("t-1", "较早的摘要", "{\"openTasks\":[\"还没做完的事\"]}")));
        // 171 (system) + 1 (request) + 40: the focus goes first, then the oldest turn pair.
        return builder.build(7L, "c1", "现在呢", emptyPolicy(), null, null, 6, 212);
    }

    private static PolicySnapshotService.PolicySnapshot emptyPolicy() {
        return new PolicySnapshotService.PolicySnapshot("ps-empty", null, List.of(), "{}", "", List.of());
    }

    private String render(BootstrapContextBuilder.BootstrapContext context) {
        StringBuilder out = new StringBuilder();
        out.append("warnings=").append(context.warnings()).append('\n');
        for (ContextSection section : context.sections()) {
            out.append("--- section ").append(section.type())
                    .append(" priority=").append(section.priority())
                    .append(" atomic=").append(section.atomic())
                    .append(" tokens=").append(section.tokenEstimate())
                    .append(" trust=").append(section.trustLevel())
                    .append(" sources=").append(section.sources().size())
                    .append('\n');
            if (!section.messages().isEmpty()) {
                section.messages().forEach(message -> out.append(message.role()).append(": ")
                        .append(message.content()).append('\n'));
            } else {
                out.append(section.content()).append('\n');
            }
        }
        for (ChatMessage message : renderer.render(context.sections())) {
            out.append("--- message ").append(message.role()).append('\n').append(message.content()).append('\n');
        }
        return out.toString();
    }

    private static AiChatMessageResponse message(Long id, String role, String content) {
        return new AiChatMessageResponse(id, "c1", null, null, role, content, null, "COMPLETED", null,
                Instant.parse("2026-10-27T00:00:00Z"), Instant.parse("2026-10-27T00:00:01Z"));
    }

    private static AiChatMessageResponse failedMessage(Long id, String role, String content) {
        return new AiChatMessageResponse(id, "c1", null, null, role, content, null, "FAILED", null,
                Instant.parse("2026-10-27T00:00:00Z"), Instant.parse("2026-10-27T00:00:01Z"));
    }

    private static AiTurnDigestEntity digest(String turnId, String summary, String structuredDigest) {
        AiTurnDigestEntity digest = new AiTurnDigestEntity();
        digest.setTurnId(turnId);
        digest.setSummary(summary);
        digest.setStructuredDigest(structuredDigest);
        return digest;
    }
}
