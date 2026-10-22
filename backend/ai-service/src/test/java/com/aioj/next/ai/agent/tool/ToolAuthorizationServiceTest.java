package com.aioj.next.ai.agent.tool;

import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.persistence.mapper.ProblemDraftWorkspaceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolAuthorizationServiceTest {

    @Test
    void problemDraftScopeRequiresServerBindingAndWorkspaceOwnershipBeforeExecution() throws Exception {
        ProblemDraftWorkspaceMapper mapper = mock(ProblemDraftWorkspaceMapper.class);
        ToolAuthorizationService service = new ToolAuthorizationService(mapper);
        ToolDescriptor descriptor = descriptor();

        var missing = service.authorize(descriptor, context(Map.of()));
        assertThat(missing.allowed()).isFalse();
        assertThat(missing.reasonCode()).isEqualTo("RESOURCE_BINDING_MISSING");

        when(mapper.selectCount(any())).thenReturn(0L, 1L);
        var denied = service.authorize(descriptor, context(Map.of("workspaceId", "ws-other")));
        var allowed = service.authorize(descriptor, context(Map.of("workspaceId", "ws-owned")));
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.reasonCode()).isEqualTo("RESOURCE_SCOPE_DENIED");
        assertThat(allowed.allowed()).isTrue();
    }

    @Test
    void workspaceLookupFailureFailsClosedWithAuthUnavailable() throws Exception {
        ProblemDraftWorkspaceMapper mapper = mock(ProblemDraftWorkspaceMapper.class);
        ToolAuthorizationService service = new ToolAuthorizationService(mapper);
        when(mapper.selectCount(any())).thenThrow(new RuntimeException("db down"));

        var decision = service.authorize(descriptor(), context(Map.of("workspaceId", "ws-1")));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo("RESOURCE_AUTH_UNAVAILABLE");
    }

    private ToolExecutionContext context(Map<String, String> bindings) {
        return new ToolExecutionContext(7L, "conversation", "turn", 1L, "policy",
                Set.of("AI_PROBLEM_DRAFT"), Instant.now(), "trace", null, bindings);
    }

    private ToolDescriptor descriptor() throws Exception {
        return new ToolDescriptor("draft.state.get", "1.0.0", "state",
                new ObjectMapper().readTree("{\"type\":\"object\"}"), ToolRiskLevel.LOW,
                true, true, Set.of("AI_PROBLEM_DRAFT"), Set.of(DataClassification.STAFF_ONLY),
                100, Duration.ofSeconds(1), ToolAuditLevel.NO_ARGUMENTS);
    }
}
