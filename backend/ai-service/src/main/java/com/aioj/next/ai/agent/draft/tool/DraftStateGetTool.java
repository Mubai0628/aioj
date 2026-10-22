package com.aioj.next.ai.agent.draft.tool;

import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.ai.agent.tool.ToolDescriptor;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolRiskLevel;
import com.aioj.next.ai.domain.draftagent.ProblemDraftWorkspaceService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class DraftStateGetTool implements AgentTool {
    private final ProblemDraftWorkspaceService workspaceService;
    private final ToolDescriptor descriptor;

    public DraftStateGetTool(ProblemDraftWorkspaceService workspaceService, ObjectMapper mapper) {
        this.workspaceService = workspaceService;
        this.descriptor = DraftToolSupport.descriptor("draft.state.get",
                "Read the authoritative mutable state and current validation summaries of this problem workspace. "
                        + "The workspace identity is server-bound and cannot be selected by arguments.",
                DraftToolSupport.objectSchema(mapper, false), ToolRiskLevel.LOW, true, true, Duration.ofSeconds(5));
    }

    @Override public ToolDescriptor descriptor() { return descriptor; }

    @Override
    public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
        return DraftToolSupport.success(workspaceService.stateForTool(
                DraftToolSupport.workspaceId(context), context.userId()));
    }
}
