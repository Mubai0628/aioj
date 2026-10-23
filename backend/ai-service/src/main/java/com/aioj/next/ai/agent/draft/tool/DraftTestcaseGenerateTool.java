package com.aioj.next.ai.agent.draft.tool;

import com.aioj.next.agent.harness.tool.api.ExecutionClass;
import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.domain.ProblemDraftTestcaseArtifactService;
import com.aioj.next.ai.domain.draftagent.ProblemDraftWorkingState;
import com.aioj.next.ai.domain.draftagent.ProblemDraftWorkspaceService;
import com.aioj.next.ai.domain.problem.DraftExecutionReport;
import com.aioj.next.ai.domain.problem.DraftSandboxClient;
import com.aioj.next.ai.domain.problem.VerificationError;
import com.aioj.next.ai.domain.problem.VerificationOptions;
import com.aioj.next.ai.domain.problem.VerificationWarning;
import com.aioj.next.ai.persistence.entity.ProblemDraftTestcaseArtifactEntity;
import com.aioj.next.ai.persistence.entity.ProblemDraftWorkspaceEntity;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class DraftTestcaseGenerateTool implements AgentTool {
    private final ProblemDraftWorkspaceService workspaceService;
    private final DraftSandboxClient sandboxClient;
    private final ProblemDraftTestcaseArtifactService artifactService;
    private final ToolDescriptor descriptor;

    public DraftTestcaseGenerateTool(ProblemDraftWorkspaceService workspaceService,
                                     DraftSandboxClient sandboxClient,
                                     ProblemDraftTestcaseArtifactService artifactService,
                                     ObjectMapper mapper) {
        this.workspaceService = workspaceService;
        this.sandboxClient = sandboxClient;
        this.artifactService = artifactService;
        ObjectNode schema = DraftToolSupport.objectSchema(mapper, false);
        DraftToolSupport.property(schema, "targetCaseCount", "integer",
                "Requested hidden case count; the server clamps it to the configured maximum.");
        this.descriptor = DraftToolSupport.descriptor("draft.testcase.generate",
                "Run the current Python testcase generator in the Sandbox. The script must create the requested "
                        + "number of non-empty .in files in the working directory; the server materializes STANDARD expected outputs, "
                        + "and publish a SHA-bound testcase artifact only when generation succeeds. This does not "
                        + "activate or import a formal problem package.",
                schema, "testcase_generate", ExecutionClass.SANDBOX, Duration.ofMinutes(6));
    }

    @Override public ToolDescriptor descriptor() { return descriptor; }

    @Override
    public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
        String workspaceId = DraftToolSupport.workspaceId(context);
        ProblemDraftWorkspaceEntity workspace = workspaceService.requireForTool(workspaceId, context.userId());
        ProblemDraftWorkingState state = workspaceService.state(workspace);
        Integer targetCount = input.has("targetCaseCount") ? input.get("targetCaseCount").asInt(0) : null;
        VerificationOptions options = new VerificationOptions(
                java.util.List.of(), state.difficulty(), workspace.getRequestedCfRating(), targetCount, false);
        DraftExecutionReport report = sandboxClient.verifyDraftDetailed(
                state.toResponse(workspace.getCandidateDraftId(), null, 0, 0), options, context.userId());
        if (report == null || report.officialPackageReport() == null || !report.officialPackageReport().passed()
                || !report.passed()) {
            // A validation failure is an authoritative tool result, not an infrastructure failure.
            // Return bounded repair evidence so the model can patch the draft and retry. Hidden
            // inputs/outputs, package file ids, manifests and internal paths are intentionally absent.
            return DraftToolSupport.success(failureResult(report));
        }
        ProblemDraftTestcaseArtifactEntity artifact = artifactService.latestReadyForDraft(workspace.getCandidateDraftId());
        if (artifact == null) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Generated testcase artifact is missing");
        }
        artifact = artifactService.bindAgentArtifact(artifact.getId(), workspaceId, context.userId(),
                workspace.getVerificationFingerprint(), DraftToolSupport.agentRunId(context));
        workspaceService.attachArtifact(workspaceId, context.userId(), artifact);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("artifactId", String.valueOf(artifact.getId()));
        result.put("status", "READY");
        result.put("formatStatus", "READY");
        result.put("executionStatus", "NOT_RUN");
        result.put("caseCount", artifact.getCaseCount());
        result.put("fileSizeBytes", artifact.getFileSizeBytes());
        result.put("sha256", artifact.getSha256());
        result.put("verificationFingerprint", artifact.getVerificationFingerprint());
        result.put("totalInputBytes", artifact.getTotalInputBytes());
        result.put("totalOutputBytes", artifact.getTotalOutputBytes());
        result.put("largestCaseBytes", artifact.getLargestCaseBytes());
        return DraftToolSupport.success(result);
    }

    private Map<String, Object> failureResult(DraftExecutionReport report) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "FAILED");
        result.put("message", "Testcase generation or its authoritative pre-publication checks did not pass; repair the reported fields and retry");
        if (report == null) {
            result.put("errors", List.of(Map.of("code", "NO_EXECUTION_REPORT",
                    "message", "The Sandbox returned no execution report")));
            return result;
        }
        if (report.sandboxReport() != null) {
            result.put("sandboxStatus", report.sandboxReport().status());
            result.put("errors", safeErrors(report.sandboxReport().errors(), false));
            result.put("warnings", safeWarnings(report.sandboxReport().warnings()));
        }
        if (report.complexityReport() != null) {
            Map<String, Object> complexity = new LinkedHashMap<>();
            complexity.put("status", report.complexityReport().status());
            complexity.put("claimedComplexity", limited(report.complexityReport().claimedComplexity()));
            complexity.put("inferredComplexity", limited(report.complexityReport().inferredComplexity()));
            complexity.put("errors", safeErrors(report.complexityReport().errors(), false));
            complexity.put("warnings", safeWarnings(report.complexityReport().warnings()));
            result.put("complexity", complexity);
        }
        if (report.crossCheckReport() != null) {
            Map<String, Object> crossCheck = new LinkedHashMap<>();
            crossCheck.put("status", report.crossCheckReport().status());
            crossCheck.put("caseCount", report.crossCheckReport().caseCount());
            crossCheck.put("errors", safeErrors(report.crossCheckReport().errors(), true));
            result.put("crossCheck", crossCheck);
        }
        if (report.officialPackageReport() != null) {
            Map<String, Object> packageResult = new LinkedHashMap<>();
            packageResult.put("status", report.officialPackageReport().status());
            packageResult.put("errorCode", report.officialPackageReport().errorCode());
            packageResult.put("errorMessage", limited(report.officialPackageReport().errorMessage()));
            packageResult.put("generatedInputCount", report.officialPackageReport().generatedInputCount());
            packageResult.put("generatedOutputCount", report.officialPackageReport().generatedOutputCount());
            packageResult.put("caseCount", report.officialPackageReport().caseCount());
            result.put("package", packageResult);
        }
        return result;
    }

    private List<Map<String, Object>> safeErrors(List<VerificationError> errors, boolean hideMismatchDetails) {
        if (errors == null) {
            return List.of();
        }
        return errors.stream().limit(12).map(error -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("code", error.code());
            value.put("field", error.field());
            String message = hideMismatchDetails && "REFERENCE_MISMATCH".equals(error.code())
                    ? "Independent reference output differed from the standard solution"
                    : error.message();
            value.put("message", limited(message));
            return value;
        }).toList();
    }

    private List<Map<String, Object>> safeWarnings(List<VerificationWarning> warnings) {
        if (warnings == null) {
            return List.of();
        }
        return warnings.stream().limit(12).map(warning -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("code", warning.code());
            value.put("field", warning.field());
            value.put("message", limited(warning.message()));
            return value;
        }).toList();
    }

    private String limited(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }
}
