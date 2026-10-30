package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.context.api.TrustLevel;
import com.aioj.next.agent.harness.tool.api.SourceRef;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.agent.harness.tool.api.ToolExecutionContext;
import com.aioj.next.agent.harness.tool.api.ToolResult;
import com.aioj.next.agent.harness.tool.api.ToolStatus;
import com.aioj.next.ai.agent.policy.ContestPolicyView;
import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.UserErrorFeedback;
import tools.jackson.databind.JsonNode;


/**
 * One ai-service tool behind the Harness tool pipeline (T1.5). A tool declares the
 * Harness descriptor itself (budget category, execution class, failure policy) and keeps
 * a typed domain view for its work, which this adapter supplies:
 * <ul>
 *   <li>the numeric user id, and the contest policy and turn sequence that travel as
 *       context attributes;</li>
 *   <li>its result with a {@link com.aioj.next.ai.agent.context.DataClassification} and a
 *       trust level, which become the Harness data label and trust;</li>
 *   <li>a {@link DomainException} becomes the same {@code DOMAIN_<code>} failure the old
 *       broker returned, with the default text of its error key instead of its message
 *       (plan S7: what the model reads it may repeat to the student).</li>
 * </ul>
 */
public final class BridgedTool implements com.aioj.next.agent.harness.tool.api.AgentTool {
    /** Context attribute holding the {@link ContestPolicyView} of a contest turn. */
    public static final String CONTEST_POLICY = "contestPolicy";
    /** Context attribute holding the turn's sequence number. */
    public static final String TURN_SEQ = "turnSeq";

    private final AgentTool tool;
    private final ToolDescriptor descriptor;

    public BridgedTool(AgentTool tool) {
        this.tool = tool;
        this.descriptor = tool.descriptor();
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
                    UserErrorFeedback.ofKey(UserErrorFeedback.storedKey(domain)).message());
        }
        return result == null ? null : harnessResult(result);
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
