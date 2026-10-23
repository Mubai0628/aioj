package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.tool.api.ExecutionClass;
import com.aioj.next.agent.harness.tool.api.FailurePolicy;
import com.aioj.next.agent.harness.tool.api.ToolAuditLevel;
import com.aioj.next.agent.harness.tool.api.ToolAuthorizer;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.agent.harness.tool.api.ToolExecutionContext;
import com.aioj.next.ai.persistence.mapper.ProblemDraftWorkspaceMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceOwnershipAuthorizerTest {
    private final ProblemDraftWorkspaceMapper mapper = mock(ProblemDraftWorkspaceMapper.class);
    private final WorkspaceOwnershipAuthorizer authorizer = new WorkspaceOwnershipAuthorizer(mapper);

    @Test
    void toolsOutsideProblemDraftsAreNotItsConcern() {
        assertThat(authorizer.authorize(descriptor("AI_CHAT"), context(Map.of())).allowed()).isTrue();
        verify(mapper, never()).selectCount(any());
    }

    @Test
    void aDraftToolNeedsAWorkspaceBinding() {
        assertThat(authorizer.authorize(descriptor("AI_PROBLEM_DRAFT"), context(Map.of())))
                .isEqualTo(ToolAuthorizer.Decision.deny("RESOURCE_BINDING_MISSING"));
    }

    @Test
    void onlyTheCreatorsWorkspaceIsAllowed() {
        when(mapper.selectCount(any())).thenReturn(0L, 1L);

        assertThat(authorizer.authorize(descriptor("AI_PROBLEM_DRAFT"), context(Map.of("workspaceId", "w1"))))
                .isEqualTo(ToolAuthorizer.Decision.deny("RESOURCE_SCOPE_DENIED"));
        assertThat(authorizer.authorize(descriptor("AI_PROBLEM_DRAFT"), context(Map.of("workspaceId", "w1")))
                .allowed()).isTrue();
    }

    @Test
    void aFailedLookupDenies() {
        when(mapper.selectCount(any())).thenThrow(new IllegalStateException("database down"));

        assertThat(authorizer.authorize(descriptor("AI_PROBLEM_DRAFT"), context(Map.of("workspaceId", "w1"))))
                .isEqualTo(ToolAuthorizer.Decision.deny("RESOURCE_AUTH_UNAVAILABLE"));
    }

    private static ToolDescriptor descriptor(String scope) {
        return new ToolDescriptor("draft.state.get", "1", "d", new ObjectMapper().createObjectNode(), Set.of(scope),
                100, Duration.ofSeconds(1), "other", ExecutionClass.READ_ONLY, FailurePolicy.FAIL_OPEN,
                ToolAuditLevel.FULL);
    }

    private static ToolExecutionContext context(Map<String, String> bindings) {
        return new ToolExecutionContext("5", "7", "c1", "t1", "ps-1", Set.of("AI_PROBLEM_DRAFT"), Instant.now(),
                "trace-1", bindings, Map.of());
    }
}
