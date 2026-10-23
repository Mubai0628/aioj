package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.context.api.TrustLevel;
import com.aioj.next.agent.harness.tool.api.ExecutionClass;
import com.aioj.next.agent.harness.tool.api.FailurePolicy;
import com.aioj.next.agent.harness.tool.api.SourceRef;
import com.aioj.next.agent.harness.tool.api.ToolAuditLevel;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.agent.harness.tool.api.ToolExecutionContext;
import com.aioj.next.agent.harness.tool.api.ToolResult;
import com.aioj.next.agent.harness.tool.api.ToolStatus;
import com.aioj.next.ai.agent.policy.ContestPolicyView;
import com.aioj.next.ai.agent.runtime.LoopBudget;
import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.common.error.DomainException;
import tools.jackson.databind.JsonNode;

import java.util.Set;

/**
 * One ai-service tool behind the Harness tool pipeline (T1.5). The tools keep their own
 * SPI until the last migration step; this keeps what the model sees and what they
 * receive unchanged meanwhile:
 * <ul>
 *   <li>the budget category is the loop budget's name rule, and sandbox categories run in
 *       the sandbox bulkhead, writes in the candidate one, reads in the read-only one;</li>
 *   <li>a contest policy and the turn sequence travel as context attributes;</li>
 *   <li>a {@link DomainException} becomes the same {@code DOMAIN_<code>} failure the old
 *       broker returned, message included.</li>
 * </ul>
 * Every tool stays {@link FailurePolicy#FAIL_OPEN}, as before.
 */
public final class BridgedTool implements com.aioj.next.agent.harness.tool.api.AgentTool {
    /** Context attribute holding the {@link ContestPolicyView} of a contest turn. */
    public static final String CONTEST_POLICY = "contestPolicy";
    /** Context attribute holding the turn's sequence number. */
    public static final String TURN_SEQ = "turnSeq";

    private static final Set<String> SANDBOX_CATEGORIES = Set.of("sandbox", "testcase_generate", "testcase_run");

    private final AgentTool tool;
    private final ToolDescriptor descriptor;

    public BridgedTool(AgentTool tool) {
        this.tool = tool;
        this.descriptor = descriptorOf(tool.descriptor());
    }

    public AgentTool legacy() {
        return tool;
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, JsonNode input) {
        com.aioj.next.ai.agent.tool.ToolResult<Object> result;
        try {
            result = tool.execute(legacyContext(context), input);
        } catch (DomainException domain) {
            return ToolResult.failure(ToolStatus.EXECUTION_ERROR, "DOMAIN_" + domain.errorCode().name(),
                    domain.getMessage());
        }
        return result == null ? null : harnessResult(result);
    }

    static ToolDescriptor descriptorOf(com.aioj.next.ai.agent.tool.ToolDescriptor legacy) {
        if (legacy == null) {
            return null;
        }
        String category = LoopBudget.categoryOf(legacy.name());
        ExecutionClass executionClass = SANDBOX_CATEGORIES.contains(category) ? ExecutionClass.SANDBOX
                : legacy.readOnly() ? ExecutionClass.READ_ONLY : ExecutionClass.WRITE_CANDIDATE;
        ToolAuditLevel auditLevel = legacy.auditLevel() == null ? null
                : ToolAuditLevel.valueOf(legacy.auditLevel().name());
        return new ToolDescriptor(legacy.name(), legacy.version(), legacy.description(), legacy.inputSchema(),
                legacy.requiredScopes(), legacy.maxResultTokens(), legacy.timeout(), category, executionClass,
                FailurePolicy.FAIL_OPEN, auditLevel);
    }

    static com.aioj.next.ai.agent.tool.ToolExecutionContext legacyContext(ToolExecutionContext context) {
        long userId = context.userId() == null ? 0L : Long.parseLong(context.userId());
        long turnSeq = context.attribute(TURN_SEQ, Long.class).orElse(0L);
        ContestPolicyView contestPolicy = context.attribute(CONTEST_POLICY, ContestPolicyView.class).orElse(null);
        return new com.aioj.next.ai.agent.tool.ToolExecutionContext(userId, context.conversationId(),
                context.turnId(), turnSeq, context.policySnapshotId(), context.grantedScopes(), context.serverTime(),
                context.traceId(), contestPolicy, context.serverBindings());
    }

    static ToolResult harnessResult(com.aioj.next.ai.agent.tool.ToolResult<?> result) {
        ToolStatus status = result.status() == null ? null : ToolStatus.valueOf(result.status().name());
        return new ToolResult(status, result.data(),
                result.sources().stream().map(source -> new SourceRef(source.type(), source.id())).toList(),
                result.classification() == null ? null : result.classification().name(),
                result.trustLevel() == null ? null : TrustLevel.valueOf(result.trustLevel().name()),
                result.truncated(), result.nextCursor(), result.warnings(), result.errorCode(), result.errorMessage());
    }
}
