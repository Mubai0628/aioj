package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.tool.api.BulkheadLimits;
import com.aioj.next.agent.harness.tool.api.ToolAuditSink;
import com.aioj.next.agent.harness.tool.api.ToolCallRequest;
import com.aioj.next.agent.harness.tool.api.ToolCatalog;
import com.aioj.next.agent.harness.tool.api.ToolExecutionContext;
import com.aioj.next.agent.harness.tool.api.ToolOutcome;
import com.aioj.next.agent.harness.tool.api.ToolRuntime;
import com.aioj.next.agent.harness.tool.api.ToolRuntimes;
import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.agent.context.TrustLevel;
import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.ai.agent.tool.SourceRef;
import com.aioj.next.ai.agent.tool.ToolAuditService;
import com.aioj.next.ai.agent.tool.ToolAuthorizationService;
import com.aioj.next.ai.agent.tool.ToolBroker;
import com.aioj.next.ai.agent.tool.ToolRegistry;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolResultSanitizer;
import com.aioj.next.ai.agent.tool.ToolStatus;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.mapper.ProblemDraftWorkspaceMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The model must see exactly what it saw through the old broker (T1.5): for each kind of
 * call, the Harness pipeline over a bridged tool renders the same payload, byte for byte.
 */
class HarnessToolPipelineParityTest {
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final Instant now = Instant.now();
    private ToolRuntime runtime;

    @AfterEach
    void close() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void aSuccessWithSourcesClassificationAndTrust() {
        assertSamePayload(tool((context, input) -> new ToolResult<>("call-1", ToolStatus.SUCCESS,
                Map.of("excerpt", "pre[r] ^ pre[l-1]", "messageId", "msg-9"),
                List.of(new SourceRef("message", "msg-9")), DataClassification.USER_PRIVATE,
                TrustLevel.USER_PROVIDED, null, false, "next-1", null, List.of("one hit"), null, null)),
                "{\"query\":\"xor\"}");
    }

    @Test
    void aResultOverItsTokenBudgetIsCutTheSameWay() {
        assertSamePayload(tool((context, input) -> ToolResult.success("call-1", "x".repeat(5000), List.of(),
                DataClassification.USER_PRIVATE, TrustLevel.SERVER_AUTHORITATIVE)), "{\"query\":\"xor\"}");
    }

    @Test
    void aFailureTheToolReportsItself() {
        assertSamePayload(tool((context, input) -> ToolResult.failure("call-1", ToolStatus.NOT_FOUND,
                "PROBLEM_NOT_VISIBLE", "Problem is not visible to you")), "{\"query\":\"xor\"}");
    }

    @Test
    void aDomainExceptionFromTheTool() {
        assertSamePayload(tool((context, input) -> {
            throw new DomainException(ErrorCode.NOT_FOUND, "Submission not found");
        }), "{\"query\":\"xor\"}");
    }

    @Test
    void argumentsThatBreakTheSchema() {
        assertSamePayload(tool((context, input) -> {
            throw new AssertionError("must not run");
        }), "{\"other\":1}");
    }

    @Test
    void argumentsThatAreNotJson() {
        assertSamePayload(tool((context, input) -> {
            throw new AssertionError("must not run");
        }), "{not json");
    }

    @Test
    void aToolTheCallerHasNoScopeFor() {
        AgentTool tool = tool((context, input) -> {
            throw new AssertionError("must not run");
        });
        String legacy = broker(tool).execute(legacyContext(Set.of()), 5L, 1, "call-1", "context.search_exact",
                "{\"query\":\"xor\"}").data();
        ToolOutcome outcome = harness(tool).execute(harnessContext(Set.of()), 1,
                new ToolCallRequest("call-1", "context.search_exact", "{\"query\":\"xor\"}"), null);

        assertThat(outcome.modelPayload()).isEqualTo(legacy);
        assertThat(outcome.modelPayload()).contains("MISSING_SCOPE");
    }

    @Test
    void anUnknownTool() {
        AgentTool tool = tool((context, input) -> null);
        String legacy = broker(tool).execute(legacyContext(Set.of("AI_CHAT")), 5L, 1, "call-1", "context.nothing",
                "{}").data();
        ToolOutcome outcome = harness(tool).execute(harnessContext(Set.of("AI_CHAT")), 1,
                new ToolCallRequest("call-1", "context.nothing", "{}"), null);

        assertThat(outcome.modelPayload()).isEqualTo(legacy);
    }

    private void assertSamePayload(AgentTool tool, String arguments) {
        String legacy = broker(tool).execute(legacyContext(Set.of("AI_CHAT")), 5L, 1, "call-1",
                "context.search_exact", arguments).data();
        ToolOutcome outcome = harness(tool).execute(harnessContext(Set.of("AI_CHAT")), 1,
                new ToolCallRequest("call-1", "context.search_exact", arguments), null);

        assertThat(outcome.modelPayload()).isEqualTo(legacy);
    }

    private AgentTool tool(BridgedToolTest.Behavior behavior) {
        return BridgedToolTest.fake("context.search_exact", true, behavior);
    }

    private ToolBroker broker(AgentTool tool) {
        return new ToolBroker(new ToolRegistry(List.of(tool)),
                new ToolAuthorizationService(mock(ProblemDraftWorkspaceMapper.class)),
                new ToolResultSanitizer(mapper), mock(ToolAuditService.class), mapper, new AiProperties());
    }

    private ToolRuntime harness(AgentTool tool) {
        runtime = ToolRuntimes.create(
                ToolCatalog.of(List.of(new BridgedTool(tool)), HarnessToolConfiguration.DRAFT_FORBIDDEN_INPUT_FIELDS),
                List.of(new WorkspaceOwnershipAuthorizer(mock(ProblemDraftWorkspaceMapper.class))),
                ToolAuditSink.NONE, BulkheadLimits.defaults(), mapper);
        return runtime;
    }

    private com.aioj.next.ai.agent.tool.ToolExecutionContext legacyContext(Set<String> scopes) {
        return new com.aioj.next.ai.agent.tool.ToolExecutionContext(7L, "c1", "t1", 1L, "ps-1", scopes, now,
                "trace-1");
    }

    private ToolExecutionContext harnessContext(Set<String> scopes) {
        return new ToolExecutionContext("5", "7", "c1", "t1", "ps-1", scopes, now, "trace-1",
                Map.of("agentRunId", "5"), Map.of(BridgedTool.TURN_SEQ, 1L));
    }
}
