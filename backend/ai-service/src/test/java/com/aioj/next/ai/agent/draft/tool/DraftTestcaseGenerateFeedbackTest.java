package com.aioj.next.ai.agent.draft.tool;

import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.domain.ProblemDraftTestcaseArtifactService;
import com.aioj.next.ai.domain.draftagent.ProblemDraftWorkingState;
import com.aioj.next.ai.domain.draftagent.ProblemDraftWorkspaceService;
import com.aioj.next.ai.domain.problem.DraftExecutionReport;
import com.aioj.next.ai.domain.problem.DraftSandboxClient;
import com.aioj.next.ai.domain.problem.OfficialTestcasePackageReport;
import com.aioj.next.ai.domain.problem.VerificationError;
import com.aioj.next.ai.domain.problem.VerificationReport;
import com.aioj.next.ai.persistence.entity.ProblemDraftTestcaseArtifactEntity;
import com.aioj.next.ai.persistence.entity.ProblemDraftWorkspaceEntity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DraftTestcaseGenerateFeedbackTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProblemDraftWorkspaceService workspaces = mock(ProblemDraftWorkspaceService.class);
    private final DraftSandboxClient sandbox = mock(DraftSandboxClient.class);
    private final ProblemDraftTestcaseArtifactService artifacts = mock(ProblemDraftTestcaseArtifactService.class);
    private final ProblemDraftWorkspaceEntity workspace = mock(ProblemDraftWorkspaceEntity.class);
    private final DraftTestcaseGenerateTool tool = new DraftTestcaseGenerateTool(workspaces, sandbox, artifacts, mapper);
    private final ToolExecutionContext context = new ToolExecutionContext(7L, "c1", "t1", 1, "p1", Set.of(),
            Instant.now(), "trace", null, Map.of("workspaceId", "w1", "agentRunId", "123"));

    private void prepareWorkspace() {
        when(workspaces.requireForTool("w1", 7L)).thenReturn(workspace);
        when(workspaces.state(workspace)).thenReturn(ProblemDraftWorkingState.fromRequest(null));
        when(workspace.getCandidateDraftId()).thenReturn(42L);
        when(workspace.getVerificationFingerprint()).thenReturn("fingerprint");
    }

    @Test
    void successfulGenerationReturnsFeedbackWithoutPretendingCasesWereJudged() {
        prepareWorkspace();
        OfficialTestcasePackageReport pack = pack("PASSED", null, null);
        when(sandbox.verifyDraftDetailed(any(), any(), eq(7L))).thenReturn(new DraftExecutionReport(
                VerificationReport.of(List.of(), List.of()), null, null, pack));
        ProblemDraftTestcaseArtifactEntity artifact = mock(ProblemDraftTestcaseArtifactEntity.class);
        when(artifact.getId()).thenReturn(99L);
        when(artifact.getCaseCount()).thenReturn(8);
        when(artifacts.latestReadyForDraft(42L)).thenReturn(artifact);
        when(artifacts.bindAgentArtifact(99L, "w1", 7L, "fingerprint", 123L)).thenReturn(artifact);

        Map<?, ?> data = (Map<?, ?>) tool.execute(context, mapper.createObjectNode()).data();
        Map<?, ?> feedback = (Map<?, ?>) data.get("feedback");
        assertThat(data.get("status")).isEqualTo("READY");
        assertThat(feedback.get("status")).isEqualTo("READY");
        assertThat(feedback.get("caseCount")).isEqualTo(8);
        assertThat(feedback.get("generatedInputCount")).isEqualTo(8);
        assertThat(feedback.get("verdict")).isNull();
        assertThat(feedback.get("timeMillis")).isNull();
        verify(workspaces).attachArtifact("w1", 7L, artifact);
    }

    @Test
    void failedGenerationReturnsCodesButNoHiddenDataOrSandboxPaths() {
        prepareWorkspace();
        String secret = "HIDDEN_INPUT_OUTPUT_123";
        String path = "/sandbox/private/run-123";
        VerificationReport failure = VerificationReport.of(List.of(new VerificationError(
                "STANDARD_OUTPUT_MATERIALIZATION_FAILED", "stderr: " + secret + " " + path,
                "testcaseGeneratorPython")), List.of());
        when(sandbox.verifyDraftDetailed(any(), any(), eq(7L))).thenReturn(new DraftExecutionReport(
                failure, null, null, pack("FAILED", "PACKAGE_FAILED", secret + path)));

        Map<?, ?> data = (Map<?, ?>) tool.execute(context, mapper.createObjectNode()).data();
        Map<?, ?> feedback = (Map<?, ?>) data.get("feedback");
        assertThat(data.get("status")).isEqualTo("FAILED");
        assertThat(feedback.get("status")).isEqualTo("FAILED");
        assertThat(feedback.get("verdict")).isNull();
        assertThat(feedback.get("errorCodes").toString()).contains("STANDARD_OUTPUT_MATERIALIZATION_FAILED", "PACKAGE_FAILED");
        assertThat(data.toString()).doesNotContain(secret, path, "scanRoot", "manifestJson");
    }

    @Test
    void absentReportStillHasAStandardFailureFeedback() {
        prepareWorkspace();
        when(sandbox.verifyDraftDetailed(any(), any(), eq(7L))).thenReturn(null);
        Map<?, ?> data = (Map<?, ?>) tool.execute(context, mapper.createObjectNode()).data();
        Map<?, ?> feedback = (Map<?, ?>) data.get("feedback");
        assertThat(feedback.get("errorCodes").toString()).contains("NO_EXECUTION_REPORT");
        assertThat(feedback.get("verdict")).isNull();
    }

    private OfficialTestcasePackageReport pack(String status, String code, String message) {
        return new OfficialTestcasePackageReport(status, code, message, "private-file", "private-name", 10L,
                "hash", 8, 8, 8, 10L, 10L, 20L, 4L, "private-manifest", "/sandbox/private/run-123", List.of());
    }
}
