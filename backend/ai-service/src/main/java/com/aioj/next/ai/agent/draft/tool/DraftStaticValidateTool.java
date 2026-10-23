package com.aioj.next.ai.agent.draft.tool;

import com.aioj.next.agent.harness.tool.api.ExecutionClass;
import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.domain.draftagent.ProblemDraftWorkspaceService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class DraftStaticValidateTool implements AgentTool {
    private final ProblemDraftWorkspaceService workspaceService;
    private final ToolDescriptor descriptor;

    public DraftStaticValidateTool(ProblemDraftWorkspaceService workspaceService, ObjectMapper mapper) {
        this.workspaceService = workspaceService;
        this.descriptor = DraftToolSupport.descriptor("draft.static.validate",
                "Run authoritative schema, field consistency, difficulty, and complexity-claim static checks against "
                        + "the current workspace state.",
                DraftToolSupport.objectSchema(mapper, false), "other", ExecutionClass.READ_ONLY,
                Duration.ofSeconds(10));
    }

    @Override public ToolDescriptor descriptor() { return descriptor; }
    @Override public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
        return DraftToolSupport.success(workspaceService.staticValidate(
                DraftToolSupport.workspaceId(context), context.userId()));
    }
}
