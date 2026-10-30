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
import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.agent.policy.ContestPolicyView;
import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.error.UserErrorFeedback;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BridgedToolTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theToolDeclaresItsOwnHarnessDescriptor() {
        AgentTool tool = fake("memory.propose_candidate", false, (context, input) -> null);

        ToolDescriptor descriptor = new BridgedTool(tool).descriptor();

        assertThat(descriptor).isSameAs(tool.descriptor());
        assertThat(descriptor.executionClass()).isEqualTo(ExecutionClass.WRITE_CANDIDATE);
        assertThat(descriptor.failurePolicy()).isEqualTo(FailurePolicy.FAIL_OPEN);
    }

    @Test
    void theToolGetsItsOwnContextBack() {
        AtomicReference<com.aioj.next.ai.agent.tool.ToolExecutionContext> seen = new AtomicReference<>();
        ContestPolicyView policy = mock(ContestPolicyView.class);
        BridgedTool tool = new BridgedTool(fake("context.search_exact", true, (context, input) -> {
            seen.set(context);
            return com.aioj.next.ai.agent.tool.ToolResult.success(null, "ok", List.of(), null, null);
        }));
        Instant now = Instant.now();

        tool.execute(new ToolExecutionContext("5", "7", "c1", "t1", "ps-1", Set.of("AI_CHAT"), now, "trace-1",
                Map.of("agentRunId", "5", "workspaceId", "w1"),
                Map.of(BridgedTool.TURN_SEQ, 3L, BridgedTool.CONTEST_POLICY, policy)), JSON.createObjectNode());

        assertThat(seen.get().userId()).isEqualTo(7L);
        assertThat(seen.get().conversationId()).isEqualTo("c1");
        assertThat(seen.get().turnId()).isEqualTo("t1");
        assertThat(seen.get().turnSeq()).isEqualTo(3L);
        assertThat(seen.get().policySnapshotId()).isEqualTo("ps-1");
        assertThat(seen.get().grantedScopes()).containsExactly("AI_CHAT");
        assertThat(seen.get().serverTime()).isEqualTo(now);
        assertThat(seen.get().traceId()).isEqualTo("trace-1");
        assertThat(seen.get().contestPolicy()).isSameAs(policy);
        assertThat(seen.get().serverBinding("workspaceId")).isEqualTo("w1");
        assertThat(seen.get().serverBinding("agentRunId")).isEqualTo("5");
    }

    @Test
    void withoutAttributesTheContestPolicyIsAbsentAndTheSequenceZero() {
        AtomicReference<com.aioj.next.ai.agent.tool.ToolExecutionContext> seen = new AtomicReference<>();
        BridgedTool tool = new BridgedTool(fake("context.search_exact", true, (context, input) -> {
            seen.set(context);
            return null;
        }));

        ToolResult result = tool.execute(context(), JSON.createObjectNode());

        assertThat(result).as("the pipeline turns a missing result into TOOL_EMPTY_RESULT").isNull();
        assertThat(seen.get().contestPolicy()).isNull();
        assertThat(seen.get().turnSeq()).isZero();
    }

    @Test
    void theResultKeepsEveryField() {
        BridgedTool tool = new BridgedTool(fake("context.search_exact", true, (context, input) ->
                new com.aioj.next.ai.agent.tool.ToolResult<>("call-1",
                        com.aioj.next.ai.agent.tool.ToolStatus.SUCCESS, Map.of("hits", 2),
                        List.of(new com.aioj.next.ai.agent.tool.SourceRef("message", "msg-1")),
                        DataClassification.USER_PRIVATE, com.aioj.next.ai.agent.context.TrustLevel.USER_PROVIDED,
                        "pd-1", true, "cursor-2", "hash", List.of("narrowed"), null, null)));

        ToolResult result = tool.execute(context(), JSON.createObjectNode());

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        assertThat(result.data()).isEqualTo(Map.of("hits", 2));
        assertThat(result.sources()).containsExactly(new SourceRef("message", "msg-1"));
        assertThat(result.dataClass()).isEqualTo("USER_PRIVATE");
        assertThat(result.trust()).isEqualTo(TrustLevel.USER_PROVIDED);
        assertThat(result.truncated()).isTrue();
        assertThat(result.nextCursor()).isEqualTo("cursor-2");
        assertThat(result.warnings()).containsExactly("narrowed");
    }

    @Test
    void aDomainFailureReachesTheModelAsItsCodeAndTheTextOfItsKey() {
        BridgedTool tool = new BridgedTool(fake("context.search_exact", true, (context, input) -> {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem not found");
        }));
        BridgedTool leaky = new BridgedTool(fake("context.search_exact", true, (context, input) -> {
            throw new DomainException(ErrorCode.INTERNAL_ERROR,
                    "AI problem context failed: I/O error on GET request for \"http://problem-service:8082/canary-41d2\"");
        }));

        ToolResult result = tool.execute(context(), JSON.createObjectNode());
        ToolResult internal = leaky.execute(context(), JSON.createObjectNode());

        assertThat(result.status()).isEqualTo(ToolStatus.EXECUTION_ERROR);
        assertThat(result.errorCode()).isEqualTo("DOMAIN_NOT_FOUND");
        assertThat(result.errorMessage()).isEqualTo(UserErrorFeedback.ofKey("problem.notFound").message());
        // Plan S7: what the model reads it may repeat to the student.
        assertThat(internal.errorMessage()).isEqualTo(UserErrorFeedback.ofKey("system.internal").message())
                .doesNotContain("problem-service", "canary-41d2", "I/O error");
    }

    static ToolExecutionContext context() {
        return new ToolExecutionContext("5", "7", "c1", "t1", "ps-1", Set.of("AI_CHAT"), Instant.now(), "trace-1",
                Map.of("agentRunId", "5"), Map.of());
    }

    interface Behavior {
        com.aioj.next.ai.agent.tool.ToolResult<Object> run(com.aioj.next.ai.agent.tool.ToolExecutionContext context,
                                                           JsonNode input);
    }

    static AgentTool fake(String name, boolean readOnly, Behavior behavior) {
        ToolDescriptor descriptor = new ToolDescriptor(name, "1.0.0", "fake tool", schema(), Set.of("AI_CHAT"), 1000,
                Duration.ofSeconds(2), name.startsWith("context.search") ? "search" : "other",
                readOnly ? ExecutionClass.READ_ONLY : ExecutionClass.WRITE_CANDIDATE, FailurePolicy.FAIL_OPEN,
                ToolAuditLevel.NO_ARGUMENTS);
        return new AgentTool() {
            @Override
            public ToolDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public com.aioj.next.ai.agent.tool.ToolResult<Object> execute(
                    com.aioj.next.ai.agent.tool.ToolExecutionContext context, JsonNode input) {
                return behavior.run(context, input);
            }
        };
    }

    static JsonNode schema() {
        return JSON.readTree("""
                {"type":"object","additionalProperties":false,
                 "required":["query"],
                 "properties":{"query":{"type":"string","minLength":1}}}
                """);
    }
}
