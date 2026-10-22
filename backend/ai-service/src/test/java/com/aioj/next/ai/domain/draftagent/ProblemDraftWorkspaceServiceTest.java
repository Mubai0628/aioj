package com.aioj.next.ai.domain.draftagent;

import com.aioj.next.ai.domain.AiConversationService;
import com.aioj.next.ai.domain.ProblemDraftStore;
import com.aioj.next.ai.domain.problem.ProblemDraftStaticValidator;
import com.aioj.next.ai.domain.problem.VerificationReport;
import com.aioj.next.ai.persistence.entity.ProblemDraftEntity;
import com.aioj.next.ai.persistence.entity.ProblemDraftWorkspaceEntity;
import com.aioj.next.ai.persistence.mapper.AiAgentRunMapper;
import com.aioj.next.ai.persistence.mapper.AiMessageMapper;
import com.aioj.next.ai.persistence.mapper.AiToolCallMapper;
import com.aioj.next.ai.persistence.mapper.ProblemDraftMapper;
import com.aioj.next.ai.persistence.mapper.ProblemDraftTestcaseArtifactMapper;
import com.aioj.next.ai.persistence.mapper.ProblemDraftWorkspaceMapper;
import com.aioj.next.contract.ai.ProblemDraftRefineRequest;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProblemDraftWorkspaceServiceTest {

    @Mock private ProblemDraftWorkspaceMapper workspaceMapper;
    @Mock private ProblemDraftMapper draftMapper;
    @Mock private ProblemDraftTestcaseArtifactMapper artifactMapper;
    @Mock private AiMessageMapper messageMapper;
    @Mock private AiAgentRunMapper agentRunMapper;
    @Mock private AiToolCallMapper toolCallMapper;
    @Mock private AiConversationService conversationService;
    @Mock private ProblemDraftStore draftStore;
    @Mock private ProblemDraftStaticValidator staticValidator;

    private ProblemDraftWorkspaceService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        service = spy(new ProblemDraftWorkspaceService(
                workspaceMapper, draftMapper, artifactMapper, messageMapper, agentRunMapper, toolCallMapper,
                conversationService, draftStore, staticValidator,
                new ProblemDraftFingerprintService(objectMapper), new ProblemDraftReviewRiskService(), objectMapper));
    }

    @Test
    void humanEditPatchesTheSameWorkspaceAndCreatesANewImmutableVersion() {
        ProblemDraftWorkspaceEntity before = workspace("ws-1", 20L, 20L, 3L);
        before.setStatus(ProblemDraftWorkspaceService.STATUS_NEEDS_ATTENTION);
        ProblemDraftWorkspaceEntity after = workspace("ws-1", 20L, 21L, 4L);
        after.setRequestedCfRating(1500);
        after.setReviewRisk("STANDARD");
        after.setContentFingerprint("c".repeat(64));
        after.setVerificationFingerprint("f".repeat(64));
        doReturn(before, after).when(service).requireOwned("ws-1", 7L);

        ProblemDraftEntity source = new ProblemDraftEntity();
        source.setId(20L);
        source.setWorkspaceId("ws-1");
        source.setModel("deepseek-v4-pro");
        when(draftMapper.selectById(20L)).thenReturn(source);

        ProblemDraftWorkspaceService.PatchOutcome patchOutcome = new ProblemDraftWorkspaceService.PatchOutcome(
                4L, "c".repeat(64), "f".repeat(64), true);
        doReturn(patchOutcome).when(service).patch(eq("ws-1"), eq(7L), eq(3L), any(JsonNode.class));
        VerificationReport staticReport = VerificationReport.of(List.of(), List.of());
        doReturn(new ProblemDraftWorkspaceService.ValidationSnapshot("f".repeat(64), staticReport))
                .when(service).staticValidate("ws-1", 7L);
        ProblemDraftWorkingState state = mock(ProblemDraftWorkingState.class);
        doReturn(state).when(service).state(after);

        ProblemDraftResponse version = mock(ProblemDraftResponse.class);
        when(version.id()).thenReturn(21L);
        when(draftStore.createHumanWorkspaceVersion(
                eq(7L), eq("ws-1"), eq(21L), eq(4L), eq(1500), eq("STANDARD"),
                eq("c".repeat(64)), eq("f".repeat(64)), eq(state), eq(staticReport), eq(20L),
                eq("teacher edit"), eq("deepseek-v4-pro"))).thenReturn(version);
        when(workspaceMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

        ProblemDraftResponse result = service.applyHumanEdit("ws-1", 7L, 20L,
                new ProblemDraftRefineRequest("Edited title", null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, "teacher edit"));

        assertThat(result.id()).isEqualTo(21L);
        ArgumentCaptor<JsonNode> patchCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).patch(eq("ws-1"), eq(7L), eq(3L), patchCaptor.capture());
        assertThat(patchCaptor.getValue().path("title").asText()).isEqualTo("Edited title");
        verify(workspaceMapper).update(isNull(), any(UpdateWrapper.class));
    }

    private ProblemDraftWorkspaceEntity workspace(String id, Long currentDraftId,
                                                   Long candidateDraftId, Long stateVersion) {
        ProblemDraftWorkspaceEntity workspace = new ProblemDraftWorkspaceEntity();
        workspace.setId(id);
        workspace.setCreatorUserId(7L);
        workspace.setCurrentDraftId(currentDraftId);
        workspace.setCandidateDraftId(candidateDraftId);
        workspace.setStateVersion(stateVersion);
        return workspace;
    }
}
