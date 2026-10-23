package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.tool.api.ToolAuditSink;
import com.aioj.next.agent.harness.tool.api.ToolCallRequest;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.agent.harness.tool.api.ToolExecutionContext;
import com.aioj.next.agent.harness.tool.api.ToolOutcome;
import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.agent.tool.ToolAuditService;
import com.aioj.next.ai.agent.tool.ToolStatus;

import java.util.Map;

/**
 * Writes the Harness pipeline's tool calls to ai_tool_calls through the existing
 * {@link ToolAuditService}, so the rows keep their columns and meaning (A2.5). The run
 * id of the Harness context is the ai_agent_runs id.
 */
public final class ToolCallAuditSink implements ToolAuditSink {
    private final ToolAuditService auditService;
    private final Map<String, com.aioj.next.ai.agent.tool.ToolDescriptor> legacyDescriptors;

    /** @param legacyDescriptors the tools' own descriptors by name, which the audit rows are built from */
    public ToolCallAuditSink(ToolAuditService auditService,
                             Map<String, com.aioj.next.ai.agent.tool.ToolDescriptor> legacyDescriptors) {
        this.auditService = auditService;
        this.legacyDescriptors = Map.copyOf(legacyDescriptors);
    }

    @Override
    public Object start(ToolExecutionContext context, int callSeq, ToolDescriptor descriptor, ToolCallRequest call,
                        String decisionId) {
        return auditService.start(BridgedTool.legacyContext(context), runId(context), callSeq,
                legacyDescriptors.get(call.toolName()), call.toolName(), call.callId(), call.argumentsJson(),
                decisionId);
    }

    @Override
    public void complete(Object handle, ToolOutcome outcome) {
        if (!(handle instanceof ToolAuditService.AuditHandle auditHandle)) {
            return;
        }
        auditService.complete(auditHandle, status(outcome), classification(outcome), outcome.modelPayload(),
                outcome.durationMillis(), outcome.result().errorCode());
    }

    @Override
    public void rejected(ToolExecutionContext context, int callSeq, ToolCallRequest call, ToolOutcome outcome) {
        auditService.record(BridgedTool.legacyContext(context), runId(context), callSeq,
                legacyDescriptors.get(call.toolName()), call.toolName(), call.callId(), call.argumentsJson(),
                outcome.decisionId(), status(outcome), classification(outcome), outcome.modelPayload(),
                outcome.durationMillis(), outcome.result().errorCode());
    }

    private static long runId(ToolExecutionContext context) {
        return context.runId() == null || context.runId().isBlank() ? 0L : Long.parseLong(context.runId());
    }

    private static ToolStatus status(ToolOutcome outcome) {
        return ToolStatus.valueOf(outcome.result().status().name());
    }

    private static DataClassification classification(ToolOutcome outcome) {
        String dataClass = outcome.result().dataClass();
        return dataClass == null ? null : DataClassification.valueOf(dataClass);
    }
}
