package com.aioj.next.ai.agent.draft.tool;

import com.aioj.next.agent.harness.tool.api.ExecutionClass;
import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.domain.ProblemDraftTestcaseArtifactService;
import com.aioj.next.ai.domain.draftagent.DraftTestcasePackageAcceptanceClient;
import com.aioj.next.ai.domain.draftagent.ProblemDraftWorkingState;
import com.aioj.next.ai.domain.draftagent.ProblemDraftWorkspaceService;
import com.aioj.next.ai.persistence.entity.ProblemDraftTestcaseArtifactEntity;
import com.aioj.next.ai.persistence.entity.ProblemDraftWorkspaceEntity;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.judge.DraftTestcasePackageRunRequest;
import com.aioj.next.contract.judge.DraftTestcasePackageRunResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class DraftTestcaseRunPackageTool implements AgentTool {
    private final ProblemDraftWorkspaceService workspaceService;
    private final ProblemDraftTestcaseArtifactService artifactService;
    private final DraftTestcasePackageAcceptanceClient acceptanceClient;
    private final ToolDescriptor descriptor;

    public DraftTestcaseRunPackageTool(ProblemDraftWorkspaceService workspaceService,
                                       ProblemDraftTestcaseArtifactService artifactService,
                                       DraftTestcasePackageAcceptanceClient acceptanceClient,
                                       ObjectMapper mapper) {
        this.workspaceService = workspaceService;
        this.artifactService = artifactService;
        this.acceptanceClient = acceptanceClient;
        ObjectNode schema = DraftToolSupport.objectSchema(mapper, false);
        DraftToolSupport.property(schema, "artifactId", "string",
                "Opaque testcase artifact id belonging to the current workspace.").put("maxLength", 32);
        DraftToolSupport.require(schema, "artifactId");
        this.descriptor = DraftToolSupport.descriptor("draft.testcase.run_package",
                "Execute every manifest case in a current, SHA-verified testcase artifact using formal STANDARD "
                        + "comparison semantics. High-difficulty drafts must also pass an independent reference "
                        + "solution. Only aggregate hidden-case failures and resources are returned.",
                schema, "testcase_run", ExecutionClass.SANDBOX, Duration.ofMinutes(10));
    }

    @Override public ToolDescriptor descriptor() { return descriptor; }

    @Override
    public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
        String workspaceId = DraftToolSupport.workspaceId(context);
        ProblemDraftWorkspaceEntity workspace = workspaceService.requireForTool(workspaceId, context.userId());
        ProblemDraftWorkingState state = workspaceService.state(workspace);
        Long artifactId;
        try {
            artifactId = Long.valueOf(input.path("artifactId").asString(""));
        } catch (NumberFormatException ex) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Invalid testcase artifact id");
        }
        ProblemDraftTestcaseArtifactEntity artifact = artifactService.requireWorkspaceArtifact(
                artifactId, workspaceId, context.userId());
        if (!"READY".equals(artifact.getFormatStatus())
                || !workspace.getVerificationFingerprint().equals(artifact.getVerificationFingerprint())) {
            throw new DomainException(ErrorCode.CONFLICT, "Testcase artifact is not READY for the current content");
        }
        boolean highDifficulty = (workspace.getRequestedCfRating() != null && workspace.getRequestedCfRating() >= 1701)
                || "HARD".equalsIgnoreCase(state.difficulty()) || "CHALLENGE".equalsIgnoreCase(state.difficulty());
        if (highDifficulty && (state.referenceSolutionCode() == null || state.referenceSolutionCode().isBlank())) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "High-difficulty testcase package acceptance requires an independent reference solution");
        }
        Path path = artifactService.resolvePath(artifact);
        DraftTestcasePackageRunResponse response = acceptanceClient.run(path, new DraftTestcasePackageRunRequest(
                artifact.getSha256(), artifact.getFileSizeBytes(), state.standardSolutionLanguage(),
                state.standardSolutionCode(), highDifficulty ? state.referenceSolutionLanguage() : null,
                highDifficulty ? state.referenceSolutionCode() : null, state.timeLimitMillis(),
                state.memoryLimitKb(), 200));
        Map<String, Object> result = safeResult(response);
        boolean passed = "PASSED".equals(response.status());
        workspaceService.storePackageValidation(workspaceId, context.userId(), artifactId, passed, result);
        return DraftToolSupport.success(result);
    }

    private Map<String, Object> safeResult(DraftTestcasePackageRunResponse response) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", response.status());
        result.put("errorCode", response.errorCode());
        result.put("message", response.message());
        result.put("packageSha256", response.packageSha256());
        result.put("packageSizeBytes", response.packageSizeBytes());
        result.put("caseCount", response.caseCount());
        result.put("executedCaseCount", response.executedCaseCount());
        result.put("passedCaseCount", response.passedCaseCount());
        result.put("failedCaseCount", response.failedCaseCount());
        result.put("failureCounts", response.failureCounts());
        result.put("maxTimeMillis", response.maxTimeMillis());
        result.put("maxMemoryKb", response.maxMemoryKb());
        result.put("maxRunTimeMillis", response.maxRunTimeMillis());
        result.put("maxProcPeak", response.maxProcPeak());
        result.put("referenceExecuted", response.referenceExecuted());
        result.put("referencePassedCaseCount", response.referencePassedCaseCount());
        return result;
    }
}
