package com.aioj.next.ai.domain.draftagent;

import com.aioj.next.ai.agent.runtime.AgentRuntime;
import com.aioj.next.ai.agent.runtime.AgentExecutionMode;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.AiConversationService;
import com.aioj.next.ai.domain.AiQuotaService;
import com.aioj.next.ai.domain.AiTurnService;
import com.aioj.next.ai.persistence.entity.AiTurnEntity;
import com.aioj.next.ai.persistence.entity.ProblemDraftGenerationJobEntity;
import com.aioj.next.ai.persistence.entity.ProblemDraftWorkspaceEntity;
import com.aioj.next.ai.persistence.mapper.AiAgentRunMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.contract.ai.AiChatMessageResponse;
import com.aioj.next.contract.ai.ProblemDraftRegenerateRequest;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProblemDraftAgentCoordinatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void workspaceContextSnapshotIsValidJsonAndContainsOnlySafeBinding() throws Exception {
        String workspaceId = "8bbad63f58354dc1bceb0fed77ff12fa";

        JsonNode snapshot = objectMapper.readTree(
                ProblemDraftAgentCoordinator.workspaceContextSnapshot(workspaceId));

        assertThat(snapshot.get("workspaceId").asString()).isEqualTo(workspaceId);
        assertThat(snapshot.get("source").asString()).isEqualTo("problem_draft_agent");
        assertThat(snapshot.size()).isEqualTo(2);
    }

    @Test
    void workspaceContextSnapshotRejectsNonUuidBinding() {
        assertThatThrownBy(() -> ProblemDraftAgentCoordinator.workspaceContextSnapshot("other-workspace"))
                .isInstanceOf(DomainException.class)
                .hasMessage("Problem draft Agent workspace id is invalid");
    }

    @Test
    void regenerationKeepsAnExistingDraftInItsOriginalWorkspace() {
        ProblemDraftWorkspaceService workspaceService = mock(ProblemDraftWorkspaceService.class);
        AiTurnService turnService = mock(AiTurnService.class);
        AiConversationService conversationService = mock(AiConversationService.class);
        ProblemDraftAgentCoordinator coordinator = new ProblemDraftAgentCoordinator(
                workspaceService,
                turnService,
                conversationService,
                mock(AgentRuntime.class),
                mock(AiAgentRunMapper.class),
                mock(AiProperties.class),
                mock(AiQuotaService.class)
        );
        String workspaceId = "8bbad63f58354dc1bceb0fed77ff12fa";
        ProblemDraftResponse source = mock(ProblemDraftResponse.class);
        when(source.id()).thenReturn(42L);
        when(source.workspaceId()).thenReturn(workspaceId);

        ProblemDraftWorkspaceEntity workspace = new ProblemDraftWorkspaceEntity();
        workspace.setId(workspaceId);
        workspace.setCreatorUserId(7L);
        workspace.setConversationId("draft-conversation");
        workspace.setCurrentDraftId(42L);
        when(workspaceService.requireOwned(workspaceId, 7L)).thenReturn(workspace);

        AiTurnEntity turn = new AiTurnEntity();
        turn.setId("draft-turn");
        turn.setConversationId("draft-conversation");
        when(turnService.beginTurn(eq("draft-conversation"), any(String.class)))
                .thenReturn(new AiTurnService.BeginTurnOutcome(turn, true));
        when(conversationService.appendMessage(eq("draft-conversation"), eq(7L), isNull(), eq("user"),
                any(String.class), isNull(), eq("draft:draft-turn:user"), any(String.class)))
                .thenReturn(new AiChatMessageResponse(9L, "draft-conversation", null,
                        "draft:draft-turn:user", "user", "feedback", null, Instant.now()));

        ProblemDraftAgentCoordinator.PreparedTurn prepared = coordinator.prepareRegeneration(
                7L, source, new ProblemDraftRegenerateRequest("strengthen edge cases"));

        assertThat(prepared.workspace().getId()).isEqualTo(workspaceId);
        assertThat(prepared.userMessage()).contains("Intent: REFINE", "strengthen edge cases");
        verify(workspaceService, never()).createForExistingDraft(any(Long.class), any(ProblemDraftResponse.class));
        verify(workspaceService).reserveTurn(workspaceId, 7L, "draft-turn", false);
    }

    @Test
    void executeReportsSuccessWhenThisRunFinalizesTheWorkspace() {
        ProblemDraftWorkspaceService workspaceService = mock(ProblemDraftWorkspaceService.class);
        AiTurnService turnService = mock(AiTurnService.class);
        AiConversationService conversationService = mock(AiConversationService.class);
        AgentRuntime agentRuntime = mock(AgentRuntime.class);
        AiProperties properties = new AiProperties();
        ProblemDraftAgentCoordinator coordinator = new ProblemDraftAgentCoordinator(
                workspaceService,
                turnService,
                conversationService,
                agentRuntime,
                mock(AiAgentRunMapper.class),
                properties,
                mock(AiQuotaService.class)
        );

        String workspaceId = "8bbad63f58354dc1bceb0fed77ff12fa";
        ProblemDraftWorkspaceEntity running = workspace(workspaceId, null, null, "RUNNING");
        ProblemDraftWorkspaceEntity finalized = workspace(workspaceId, 42L, 101L, "PENDING_REVIEW");
        finalized.setStateVersion(4L);
        finalized.setReviewRisk("STANDARD_REVIEW");
        finalized.setContentFingerprint("content-fingerprint");
        finalized.setVerificationFingerprint("verification-fingerprint");
        when(workspaceService.requireOwned(workspaceId, 7L)).thenReturn(running, finalized, finalized);

        AiTurnEntity turn = new AiTurnEntity();
        turn.setId("draft-turn");
        turn.setConversationId("draft-conversation");
        turn.setUserMessageId("9");
        when(turnService.findById("draft-turn")).thenReturn(turn);
        when(turnService.advanceToGenerating("draft-turn", "draft-turn")).thenReturn(true);

        AiChatMessageResponse userMessage = mock(AiChatMessageResponse.class);
        when(userMessage.id()).thenReturn(9L);
        when(userMessage.content()).thenReturn("Create a validated problem");
        when(conversationService.getMessage(7L, 9L)).thenReturn(userMessage);
        when(conversationService.messages(7L, "draft-conversation")).thenReturn(List.of(userMessage));
        AiChatMessageResponse assistantMessage = mock(AiChatMessageResponse.class);
        when(assistantMessage.id()).thenReturn(10L);
        when(conversationService.appendMessage(eq("draft-conversation"), eq(7L), isNull(), eq("assistant"),
                any(String.class), eq("deepseek-v4-pro"), eq("draft:draft-turn:assistant"), any(String.class)))
                .thenReturn(assistantMessage);

        when(agentRuntime.run(any(AgentRuntime.AgentRunRequest.class))).thenReturn(
                new AgentRuntime.AgentRunResult(
                        101L,
                        "Draft finalized for human review.",
                        "deepseek",
                        "deepseek-v4-pro",
                        1200,
                        400,
                        0,
                        9,
                        8,
                        false,
                        List.of(),
                        null,
                        AgentExecutionMode.STANDARD_TOOL_FALLBACK
                ));

        ProblemDraftGenerationJobEntity job = new ProblemDraftGenerationJobEntity();
        job.setId(77L);
        job.setCreatorUserId(7L);
        job.setWorkspaceId(workspaceId);
        job.setTurnId("draft-turn");

        ProblemDraftAgentCoordinator.AgentOutcome outcome = coordinator.execute(job);

        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.draftId()).isEqualTo(42L);
        assertThat(outcome.stage()).isEqualTo("FINALIZED");
        assertThat(outcome.agentMode()).isEqualTo("STANDARD_TOOL_FALLBACK");
        verify(workspaceService).storeFinalizedAgentUsage(
                workspaceId, 7L, 42L, 101L, "deepseek-v4-pro", 1200, 400);
        verify(turnService).completeTurn("draft-turn");
    }

    private ProblemDraftWorkspaceEntity workspace(String id, Long draftId, Long runId, String status) {
        ProblemDraftWorkspaceEntity workspace = new ProblemDraftWorkspaceEntity();
        workspace.setId(id);
        workspace.setCreatorUserId(7L);
        workspace.setConversationId("draft-conversation");
        workspace.setCurrentDraftId(draftId);
        workspace.setCurrentAgentRunId(runId);
        workspace.setStatus(status);
        return workspace;
    }
}
