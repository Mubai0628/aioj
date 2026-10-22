package com.aioj.next.ai.agent.draft.tool;

import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.ai.agent.tool.ToolDescriptor;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolRiskLevel;
import com.aioj.next.ai.domain.draftagent.ProblemDraftExecutionService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class DraftExecutionGetTool implements AgentTool {
    private final ProblemDraftExecutionService executionService;
    private final ToolDescriptor descriptor;

    public DraftExecutionGetTool(ProblemDraftExecutionService executionService, ObjectMapper mapper) {
        this.executionService = executionService;
        ObjectNode schema = DraftToolSupport.objectSchema(mapper, false);
        DraftToolSupport.property(schema, "executionId", "string", "Opaque id returned by draft.code.execute.")
                .put("maxLength", 64);
        DraftToolSupport.property(schema, "offset", "integer", "Character offset for bounded output retrieval.");
        DraftToolSupport.property(schema, "limit", "integer", "Maximum output characters, server-capped at 65536.");
        DraftToolSupport.require(schema, "executionId");
        this.descriptor = DraftToolSupport.descriptor("draft.execution.get",
                "Read a bounded segment of stdout/stderr and resource metrics for an execution that belongs to the "
                        + "current workspace. Cross-workspace ids are rejected.",
                schema, ToolRiskLevel.LOW, true, true, Duration.ofSeconds(5));
    }

    @Override public ToolDescriptor descriptor() { return descriptor; }
    @Override public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
        return DraftToolSupport.success(executionService.result(DraftToolSupport.workspaceId(context),
                context.userId(), input.path("executionId").asString(""),
                input.has("offset") ? input.get("offset").asInt(0) : null,
                input.has("limit") ? input.get("limit").asInt(0) : null));
    }
}
