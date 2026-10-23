package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.tool.api.ToolAuthorizer;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.agent.harness.tool.api.ToolExecutionContext;
import com.aioj.next.ai.persistence.entity.ProblemDraftWorkspaceEntity;
import com.aioj.next.ai.persistence.mapper.ProblemDraftWorkspaceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

/**
 * Problem-draft tools act only on the workspace the server bound to the run, and only
 * when the caller created it. Fails closed: no binding, a foreign workspace and a failed
 * lookup are all denials (the reason codes of the old authorization service).
 */
public final class WorkspaceOwnershipAuthorizer implements ToolAuthorizer {
    static final String PROBLEM_DRAFT_SCOPE = "AI_PROBLEM_DRAFT";

    private final ProblemDraftWorkspaceMapper workspaceMapper;

    public WorkspaceOwnershipAuthorizer(ProblemDraftWorkspaceMapper workspaceMapper) {
        this.workspaceMapper = workspaceMapper;
    }

    @Override
    public Decision authorize(ToolDescriptor descriptor, ToolExecutionContext context) {
        if (!descriptor.requiredScopes().contains(PROBLEM_DRAFT_SCOPE)) {
            return Decision.allow();
        }
        String workspaceId = context.serverBinding("workspaceId");
        if (workspaceId == null || workspaceId.isBlank()) {
            return Decision.deny("RESOURCE_BINDING_MISSING");
        }
        try {
            Long owned = workspaceMapper.selectCount(new LambdaQueryWrapper<ProblemDraftWorkspaceEntity>()
                    .eq(ProblemDraftWorkspaceEntity::getId, workspaceId)
                    .eq(ProblemDraftWorkspaceEntity::getCreatorUserId, Long.parseLong(context.userId())));
            return owned != null && owned == 1L ? Decision.allow() : Decision.deny("RESOURCE_SCOPE_DENIED");
        } catch (RuntimeException ex) {
            return Decision.deny("RESOURCE_AUTH_UNAVAILABLE");
        }
    }
}
