package com.aioj.next.ai.agent.draft.tool;

import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.ai.agent.tool.ToolDescriptor;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolRiskLevel;
import com.aioj.next.ai.domain.draftagent.ProblemDraftWorkspaceService;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class DraftFinalizeTool implements AgentTool {
    private final ProblemDraftWorkspaceService workspaceService;
    private final ToolDescriptor descriptor;

    public DraftFinalizeTool(ProblemDraftWorkspaceService workspaceService, ObjectMapper mapper) {
        this.workspaceService = workspaceService;
        this.descriptor = DraftToolSupport.descriptor("draft.finalize",
                "Finalize the current fingerprint only after authoritative static validation, public samples, "
                        + "testcase format, and full package execution have passed. Creates PENDING_REVIEW only; "
                        + "never approves, imports, activates, or publishes a problem.",
                DraftToolSupport.objectSchema(mapper, false), ToolRiskLevel.HIGH, false, true,
                Duration.ofSeconds(20));
    }

    @Override public ToolDescriptor descriptor() { return descriptor; }
    @Override public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
        ProblemDraftResponse draft = workspaceService.finalizeCurrent(DraftToolSupport.workspaceId(context),
                context.userId(), DraftToolSupport.agentRunId(context), null, 0, 0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("draftId", String.valueOf(draft.id()));
        result.put("status", draft.status());
        result.put("validationStatus", draft.validationStatus());
        result.put("verificationStatus", draft.verificationStatus());
        result.put("message", "Draft finalized for human review; no import or publication was performed");
        return DraftToolSupport.success(result);
    }
}
