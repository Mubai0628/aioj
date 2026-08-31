package com.aioj.next.ai.agent.draft.tool;

import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.ai.agent.tool.ToolAuditLevel;
import com.aioj.next.ai.agent.tool.ToolInputSchemaValidator;
import com.aioj.next.ai.agent.tool.ToolRegistry;
import com.aioj.next.ai.domain.ProblemDraftTestcaseArtifactService;
import com.aioj.next.ai.domain.draftagent.DraftTestcasePackageAcceptanceClient;
import com.aioj.next.ai.domain.draftagent.ProblemDraftExecutionService;
import com.aioj.next.ai.domain.draftagent.ProblemDraftWorkspaceService;
import com.aioj.next.ai.domain.problem.DraftSandboxClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DraftAgentToolContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProblemDraftWorkspaceService workspaceService = mock(ProblemDraftWorkspaceService.class);
    private final ProblemDraftExecutionService executionService = mock(ProblemDraftExecutionService.class);
    private final DraftSandboxClient sandboxClient = mock(DraftSandboxClient.class);
    private final ProblemDraftTestcaseArtifactService artifactService = mock(ProblemDraftTestcaseArtifactService.class);
    private final DraftTestcasePackageAcceptanceClient acceptanceClient =
            mock(DraftTestcasePackageAcceptanceClient.class);

    @Test
    void exposesExactlyNineWorkspaceBoundToolsWithProtectedAuditContracts() {
        List<AgentTool> tools = tools();

        // Registry construction also verifies the supported JSON-Schema subset and forbids
        // model-controlled identity/resource binding fields.
        new ToolRegistry(tools);

        assertThat(tools.stream().map(tool -> tool.descriptor().name()).collect(Collectors.toSet()))
                .isEqualTo(Set.of(
                        "draft.state.get",
                        "draft.state.patch",
                        "draft.static.validate",
                        "draft.code.execute",
                        "draft.execution.get",
                        "draft.samples.run",
                        "draft.testcase.generate",
                        "draft.testcase.run_package",
                        "draft.finalize"
                ));
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.descriptor().requiredScopes()).containsExactly("AI_PROBLEM_DRAFT");
            assertThat(tool.descriptor().auditLevel()).isEqualTo(ToolAuditLevel.NO_ARGUMENTS);
            String schema = tool.descriptor().inputSchema().toString();
            assertThat(schema).doesNotContain("workspaceId", "userId", "generationJobId");
        });
    }

    @Test
    void statePatchSchemaRejectsUnknownFieldsAndEnforcesRealArrayAndNumericBounds() throws Exception {
        AgentTool patchTool = tools().stream()
                .filter(tool -> "draft.state.patch".equals(tool.descriptor().name()))
                .findFirst().orElseThrow();

        assertThat(ToolInputSchemaValidator.validate(patchTool.descriptor().inputSchema(), mapper.readTree("""
                {"expectedStateVersion":1,"patch":{"workspaceId":"other"}}
                """))).anyMatch(error -> error.contains("is not allowed"));
        assertThat(ToolInputSchemaValidator.validate(patchTool.descriptor().inputSchema(), mapper.readTree("""
                {"expectedStateVersion":1,"patch":{"timeLimitMillis":60001}}
                """))).anyMatch(error -> error.contains("greater than maximum"));

        StringBuilder tags = new StringBuilder();
        for (int index = 0; index < 31; index++) {
            if (index > 0) tags.append(',');
            tags.append('"').append(index).append('"');
        }
        assertThat(ToolInputSchemaValidator.validate(patchTool.descriptor().inputSchema(),
                mapper.readTree("{\"expectedStateVersion\":1,\"patch\":{\"tags\":[" + tags + "]}}")))
                .anyMatch(error -> error.contains("more than maxItems"));
    }

    private List<AgentTool> tools() {
        return List.of(
                new DraftStateGetTool(workspaceService, mapper),
                new DraftStatePatchTool(workspaceService, mapper),
                new DraftStaticValidateTool(workspaceService, mapper),
                new DraftCodeExecuteTool(workspaceService, executionService, mapper),
                new DraftExecutionGetTool(executionService, mapper),
                new DraftSamplesRunTool(workspaceService, sandboxClient, mapper),
                new DraftTestcaseGenerateTool(workspaceService, sandboxClient, artifactService, mapper),
                new DraftTestcaseRunPackageTool(workspaceService, artifactService, acceptanceClient, mapper),
                new DraftFinalizeTool(workspaceService, mapper)
        );
    }
}
