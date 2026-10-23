package com.aioj.next.ai.agent.draft.tool;

import com.aioj.next.agent.harness.tool.api.ExecutionClass;
import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.domain.draftagent.ProblemDraftExecutionService;
import com.aioj.next.ai.domain.draftagent.ProblemDraftWorkingState;
import com.aioj.next.ai.domain.draftagent.ProblemDraftWorkspaceService;
import com.aioj.next.ai.persistence.entity.ProblemDraftExecutionEntity;
import com.aioj.next.ai.persistence.entity.ProblemDraftWorkspaceEntity;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class DraftCodeExecuteTool implements AgentTool {
    private final ProblemDraftWorkspaceService workspaceService;
    private final ProblemDraftExecutionService executionService;
    private final ToolDescriptor descriptor;

    public DraftCodeExecuteTool(ProblemDraftWorkspaceService workspaceService,
                                ProblemDraftExecutionService executionService,
                                ObjectMapper mapper) {
        this.workspaceService = workspaceService;
        this.executionService = executionService;
        ObjectNode schema = DraftToolSupport.objectSchema(mapper, false);
        ObjectNode purpose = DraftToolSupport.property(schema, "purpose", "string",
                "STANDARD, REFERENCE, or PROBE. STANDARD/REFERENCE always use the server's current code.");
        ArrayNode purposeValues = purpose.putArray("enum");
        purposeValues.add("STANDARD").add("REFERENCE").add("PROBE");
        DraftToolSupport.property(schema, "language", "string",
                "Required only for PROBE: cpp, java, or python.").put("maxLength", 32);
        DraftToolSupport.property(schema, "code", "string",
                "Required only for PROBE. Ignored for STANDARD/REFERENCE.").put("maxLength", 262144);
        DraftToolSupport.property(schema, "stdin", "string", "Bounded process standard input.")
                .put("maxLength", 8388608);
        DraftToolSupport.property(schema, "timeLimitMillis", "integer", "Requested time limit; server clamps it.");
        DraftToolSupport.property(schema, "memoryLimitKb", "integer", "Requested memory limit; server clamps it.");
        DraftToolSupport.require(schema, "purpose", "stdin");
        this.descriptor = DraftToolSupport.descriptor("draft.code.execute",
                "Execute the current standard solution, independent reference solution, or a bounded probe in the "
                        + "Docker Sandbox. Returns an opaque executionId and resource summary; use draft.execution.get "
                        + "for bounded stdout/stderr.", schema, "sandbox", ExecutionClass.SANDBOX, Duration.ofMinutes(2));
    }

    @Override public ToolDescriptor descriptor() { return descriptor; }

    @Override
    public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
        String workspaceId = DraftToolSupport.workspaceId(context);
        ProblemDraftWorkspaceEntity workspace = workspaceService.requireForTool(workspaceId, context.userId());
        ProblemDraftWorkingState state = workspaceService.state(workspace);
        String purpose = input.path("purpose").asString("");
        String language;
        String code;
        switch (purpose) {
            case "STANDARD" -> {
                language = state.standardSolutionLanguage();
                code = state.standardSolutionCode();
            }
            case "REFERENCE" -> {
                language = state.referenceSolutionLanguage();
                code = state.referenceSolutionCode();
                if (code == null || code.isBlank()) {
                    throw new DomainException(ErrorCode.VALIDATION_FAILED,
                            "Current problem draft has no independent reference solution");
                }
            }
            case "PROBE" -> {
                language = input.path("language").asString(null);
                code = input.path("code").asString(null);
                if (language == null || code == null || code.isBlank()) {
                    throw new DomainException(ErrorCode.BAD_REQUEST, "PROBE execution requires language and code");
                }
            }
            default -> throw new DomainException(ErrorCode.BAD_REQUEST, "Unsupported execution purpose");
        }
        ProblemDraftExecutionEntity execution = executionService.execute(workspaceId, context.userId(),
                DraftToolSupport.agentRunId(context), null, purpose, language, code,
                input.path("stdin").asString(""), nullableInt(input, "timeLimitMillis", state.timeLimitMillis()),
                nullableInt(input, "memoryLimitKb", state.memoryLimitKb()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executionId", execution.getId());
        result.put("status", execution.getStatus());
        result.put("timeMillis", execution.getTimeMillis());
        result.put("memoryKb", execution.getMemoryKb());
        result.put("procPeak", execution.getProcPeak());
        result.put("exitStatus", execution.getExitStatus());
        result.put("runTimeMillis", execution.getRunTimeMillis());
        result.put("outputTruncated", execution.getOutputTruncated());
        result.put("resultHash", execution.getResultHash());
        return DraftToolSupport.success(result);
    }

    private Integer nullableInt(JsonNode input, String field, Integer fallback) {
        return input.has(field) && input.get(field).isIntegralNumber() ? input.get(field).asInt(0) : fallback;
    }
}
