package com.aioj.next.ai.agent.draft.tool;

import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.agent.context.TrustLevel;
import com.aioj.next.ai.agent.tool.ToolAuditLevel;
import com.aioj.next.ai.agent.tool.ToolDescriptor;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolRiskLevel;
import com.aioj.next.ai.domain.draftagent.ProblemDraftWorkspaceService;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.List;
import java.util.Set;

final class DraftToolSupport {
    private DraftToolSupport() {
    }

    static String workspaceId(ToolExecutionContext context) {
        String workspaceId = context.serverBinding("workspaceId");
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Problem draft workspace binding is missing");
        }
        return workspaceId;
    }

    static Long agentRunId(ToolExecutionContext context) {
        String value = context.serverBinding("agentRunId");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static ToolResult<Object> success(Object data) {
        return ToolResult.success(null, data, List.of(), DataClassification.STAFF_ONLY,
                TrustLevel.SERVER_AUTHORITATIVE);
    }

    static ToolDescriptor descriptor(String name, String description, JsonNode schema,
                                     ToolRiskLevel risk, boolean readOnly, boolean idempotent,
                                     Duration timeout) {
        return new ToolDescriptor(name, "1.0.0", description, schema, risk, readOnly, idempotent,
                Set.of(ProblemDraftWorkspaceService.SCOPE), Set.of(DataClassification.STAFF_ONLY),
                4000, timeout, ToolAuditLevel.NO_ARGUMENTS);
    }

    static ObjectNode objectSchema(ObjectMapper mapper, boolean additionalProperties) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", mapper.createObjectNode());
        schema.set("required", mapper.createArrayNode());
        schema.put("additionalProperties", additionalProperties);
        return schema;
    }

    static ObjectNode property(ObjectNode schema, String name, String type, String description) {
        ObjectNode value = schema.objectNode();
        value.put("type", type);
        value.put("description", description);
        ((ObjectNode) schema.get("properties")).set(name, value);
        return value;
    }

    static void require(ObjectNode schema, String... names) {
        ArrayNode required = (ArrayNode) schema.get("required");
        for (String name : names) {
            required.add(name);
        }
    }
}
