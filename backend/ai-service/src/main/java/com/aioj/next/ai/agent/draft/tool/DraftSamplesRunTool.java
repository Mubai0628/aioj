package com.aioj.next.ai.agent.draft.tool;

import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.ai.agent.tool.ToolDescriptor;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolRiskLevel;
import com.aioj.next.ai.domain.draftagent.ProblemDraftWorkingState;
import com.aioj.next.ai.domain.draftagent.ProblemDraftWorkspaceService;
import com.aioj.next.ai.domain.problem.DraftSandboxClient;
import com.aioj.next.ai.persistence.entity.ProblemDraftWorkspaceEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class DraftSamplesRunTool implements AgentTool {
    private final ProblemDraftWorkspaceService workspaceService;
    private final DraftSandboxClient sandboxClient;
    private final ToolDescriptor descriptor;

    public DraftSamplesRunTool(ProblemDraftWorkspaceService workspaceService,
                               DraftSandboxClient sandboxClient, ObjectMapper mapper) {
        this.workspaceService = workspaceService;
        this.sandboxClient = sandboxClient;
        this.descriptor = DraftToolSupport.descriptor("draft.samples.run",
                "Compile the current standard solution and run every public sample with authoritative output "
                        + "comparison. Returns per-field error summaries and aggregate resources, not internal file ids.",
                DraftToolSupport.objectSchema(mapper, false), ToolRiskLevel.MEDIUM, false, false,
                Duration.ofMinutes(3));
    }

    @Override public ToolDescriptor descriptor() { return descriptor; }

    @Override
    public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
        String workspaceId = DraftToolSupport.workspaceId(context);
        ProblemDraftWorkspaceEntity workspace = workspaceService.requireForTool(workspaceId, context.userId());
        ProblemDraftWorkingState state = workspaceService.state(workspace);
        DraftSandboxClient.SampleExecutionResult run = sandboxClient.runSamples(
                state.toResponse(workspace.getCandidateDraftId(), null, 0, 0));
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("passedCaseCount", run.passedCaseCount());
        metrics.put("totalCaseCount", run.totalCaseCount());
        metrics.put("maxTimeMillis", run.maxTimeMillis());
        metrics.put("maxMemoryKb", run.maxMemoryKb());
        metrics.put("maxProcessCount", run.maxProcessCount());
        workspaceService.storeSamplesValidation(workspaceId, context.userId(), run.report(), metrics);
        Map<String, Object> result = new LinkedHashMap<>(metrics);
        result.put("status", run.report().status());
        result.put("errors", run.report().errors());
        result.put("warnings", run.report().warnings());
        result.put("verificationFingerprint", workspace.getVerificationFingerprint());
        return DraftToolSupport.success(result);
    }
}
