package com.aioj.next.ai.agent.context;

import com.aioj.next.ai.domain.AiConversationService;
import com.aioj.next.ai.domain.clarification.ClarificationSchemaRepairer;
import com.aioj.next.ai.domain.response.AiAssistantResponseNormalizer;
import com.aioj.next.ai.persistence.entity.AiAgentRunEntity;
import com.aioj.next.ai.persistence.entity.AiContextManifestEntity;
import com.aioj.next.ai.persistence.entity.AiMessageEntity;
import com.aioj.next.ai.persistence.mapper.AiAgentRunMapper;
import com.aioj.next.ai.persistence.mapper.AiContextManifestMapper;
import com.aioj.next.ai.persistence.mapper.AiMessageMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiConversationContextDebugResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ContextDebugServiceTest {
    private static final Long USER_ID = 7L;
    private static final String CONVERSATION_ID = "c-1";
    private static final String SECTIONS = """
            [{"type":"SYSTEM_POLICY","tokenEstimate":400,"trust":"SYSTEM_POLICY","atomic":true},
             {"type":"RECENT_TURNS","tokenEstimate":300,"trust":"USER_PROVIDED","atomic":false,"messageCount":6},
             {"type":"BOOTSTRAP_CONTEXT","tokenEstimate":200,"trust":"DERIVED_SUMMARY","atomic":false}]""";

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final AiConversationService conversationService = mock(AiConversationService.class);
    private final AiAgentRunMapper runMapper = mock(AiAgentRunMapper.class);
    private final AiContextManifestMapper manifestMapper = mock(AiContextManifestMapper.class);
    private final AiMessageMapper messageMapper = mock(AiMessageMapper.class);
    private final ContextDebugService service = new ContextDebugService(conversationService, runMapper, manifestMapper,
            messageMapper, new AiAssistantResponseNormalizer(objectMapper, new ClarificationSchemaRepairer()), objectMapper);

    @Test
    void reportsTheSectionsAndTotalsOfTheLatestModelCall() {
        when(runMapper.selectList(any())).thenReturn(List.of(run(11L)));
        when(manifestMapper.selectOne(any())).thenReturn(manifest(11L, SECTIONS, "[\"contributor_degraded:memory\"]"));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message(2L, "assistant", "这是一个很长的回答" + "啊".repeat(200), 2),
                message(1L, "user", "为什么   超时了？", 1)));

        AiConversationContextDebugResponse response = service.contextDebug(USER_ID, CONVERSATION_ID);

        assertThat(response.conversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(response.userId()).isEqualTo("7");
        assertThat(response.sections()).extracting(AiConversationContextDebugResponse.ContextSection::type)
                .containsExactly("SYSTEM_POLICY", "RECENT_TURNS", "BOOTSTRAP_CONTEXT");
        assertThat(response.sections().get(0).required()).isTrue();
        assertThat(response.sections().get(1).metadata()).containsEntry("messageCount", 6);
        assertThat(response.sections()).allSatisfy(section -> assertThat(section.contentPreview()).isEmpty());

        AiConversationContextDebugResponse.ContextBuildReport report = response.contextBuildReport();
        assertThat(report.totalEstimatedTokens()).isEqualTo(900);
        assertThat(report.requiredEstimatedTokens()).isEqualTo(400);
        assertThat(report.optionalEstimatedTokens()).isEqualTo(500);
        assertThat(report.requiredSectionCount()).isEqualTo(1);
        assertThat(report.optionalSectionCount()).isEqualTo(2);
        assertThat(report.budget().model()).isEqualTo("deepseek-v4-flash");
        assertThat(report.budget().estimatedBySection()).containsEntry("RECENT_TURNS", 300);
        assertThat(response.sourceSummary()).containsEntry("BOOTSTRAP_CONTEXT", 1);
        assertThat(response.tokenEstimate()).isEqualTo(new AiConversationContextDebugResponse.TokenEstimate(300, 0, 0, 200, 900));
        assertThat(response.warnings()).extracting(AiConversationContextDebugResponse.Warning::code)
                .containsExactly("contributor_degraded:memory");

        assertThat(response.contextPackPreview())
                .contains("call 2", "provider input tokens 950, cache hit 512", "RECENT_TURNS 300 tokens USER_PROVIDED")
                .doesNotContain("为什么");
        assertThat(response.recentMessages()).extracting(AiConversationContextDebugResponse.RecentMessage::id)
                .containsExactly("1", "2");
        assertThat(response.recentMessages().get(0).contentPreview()).isEqualTo("为什么 超时了？");
        assertThat(response.recentMessages().get(1).contentPreview()).hasSize(ContextDebugService.PREVIEW_CHARS);
        assertThat(response.state()).isEmpty();
        assertThat(response.pendingClarifications()).isEmpty();
        assertThat(response.summarySegments()).isEmpty();
    }

    @Test
    void fallsBackToAnEarlierRunWhenTheNewestFailedBeforeCallingTheModel() {
        when(runMapper.selectList(any())).thenReturn(List.of(run(12L), run(11L)));
        // The newest run (12) has no manifest; the lookup for run 11 finds one.
        when(manifestMapper.selectOne(any())).thenReturn(null, manifest(11L, SECTIONS, "[]"));
        when(messageMapper.selectList(any())).thenReturn(List.of());

        AiConversationContextDebugResponse response = service.contextDebug(USER_ID, CONVERSATION_ID);

        assertThat(response.sections()).hasSize(3);
        assertThat(response.contextPackPreview()).contains("run 11");
        assertThat(response.warnings()).isEmpty();
    }

    @Test
    void saysSoWhenNoModelCallRecordedAManifest() {
        when(runMapper.selectList(any())).thenReturn(List.of());
        when(messageMapper.selectList(any())).thenReturn(List.of(message(1L, "user", "hi", 1)));

        AiConversationContextDebugResponse response = service.contextDebug(USER_ID, CONVERSATION_ID);

        assertThat(response.sections()).isEmpty();
        assertThat(response.contextBuildReport().totalEstimatedTokens()).isZero();
        assertThat(response.recentMessages()).hasSize(1);
        assertThat(response.warnings()).extracting(AiConversationContextDebugResponse.Warning::code)
                .containsExactly("NO_MANIFEST");
    }

    @Test
    void unreadableManifestJsonLeavesTheSectionsEmptyInsteadOfFailing() {
        when(runMapper.selectList(any())).thenReturn(List.of(run(11L)));
        when(manifestMapper.selectOne(any())).thenReturn(manifest(11L, "not json", "also not json"));
        when(messageMapper.selectList(any())).thenReturn(List.of());

        AiConversationContextDebugResponse response = service.contextDebug(USER_ID, CONVERSATION_ID);

        assertThat(response.sections()).isEmpty();
        assertThat(response.warnings()).isEmpty();
        assertThat(response.contextPackPreview()).contains("model deepseek-v4-flash");
    }

    @Test
    void checksOwnershipBeforeReadingAnything() {
        when(conversationService.getOwnedConversation(eq(USER_ID), eq("c-other")))
                .thenThrow(new DomainException(ErrorCode.FORBIDDEN, "AI conversation belongs to another user"));

        assertThatThrownBy(() -> service.contextDebug(USER_ID, "c-other")).isInstanceOf(DomainException.class);
        verifyNoInteractions(runMapper, manifestMapper, messageMapper);
    }

    private static AiAgentRunEntity run(Long id) {
        AiAgentRunEntity run = new AiAgentRunEntity();
        run.setId(id);
        run.setConversationId(CONVERSATION_ID);
        run.setUserId(USER_ID);
        return run;
    }

    private static AiContextManifestEntity manifest(Long runId, String sections, String warnings) {
        AiContextManifestEntity manifest = new AiContextManifestEntity();
        manifest.setId(runId * 10);
        manifest.setTurnId("turn-" + runId);
        manifest.setAgentRunId(runId);
        manifest.setCallSeq(2);
        manifest.setModel("deepseek-v4-flash");
        manifest.setPromptVersion("tutor-v3");
        manifest.setSectionsJson(sections);
        manifest.setWarningsJson(warnings);
        manifest.setInputTokens(950);
        manifest.setCacheHitTokens(512);
        return manifest;
    }

    private static AiMessageEntity message(Long id, String role, String content, int minute) {
        AiMessageEntity message = new AiMessageEntity();
        message.setId(id);
        message.setConversationId(CONVERSATION_ID);
        message.setUserId(USER_ID);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.of(2026, 10, 23, 9, minute));
        return message;
    }
}
