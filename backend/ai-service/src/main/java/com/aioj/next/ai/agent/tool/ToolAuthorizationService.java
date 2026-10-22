package com.aioj.next.ai.agent.tool;

import com.aioj.next.ai.persistence.entity.ProblemDraftWorkspaceEntity;
import com.aioj.next.ai.persistence.mapper.ProblemDraftWorkspaceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

/**
 * Per-call authorization: every tool invocation is independently authorized
 * against the server-generated execution context (never model claims).
 * P0 scope: required-scope checks. Contest/resource ABAC arrives in P3.
 *
 * <p>Fail-closed: the workspace mapper is mandatory, so the ownership check
 * can never be silently skipped; a lookup failure denies the call.</p>
 */
@Service
public class ToolAuthorizationService {

    private static final String PROBLEM_DRAFT_SCOPE = "AI_PROBLEM_DRAFT";
    private final ProblemDraftWorkspaceMapper workspaceMapper;

    public ToolAuthorizationService(ProblemDraftWorkspaceMapper workspaceMapper) {
        this.workspaceMapper = workspaceMapper;
    }

    public record AuthorizationDecision(boolean allowed, String reasonCode, String decisionId) {
    }

    public AuthorizationDecision authorize(ToolDescriptor descriptor, ToolExecutionContext context) {
        String decisionId = "pd-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Set<String> required = descriptor.requiredScopes();
        Set<String> granted = context.grantedScopes();
        if (!required.isEmpty() && !granted.containsAll(required)) {
            return new AuthorizationDecision(false, "MISSING_SCOPE", decisionId);
        }
        if (required.contains(PROBLEM_DRAFT_SCOPE)) {
            String workspaceId = context.serverBinding("workspaceId");
            if (workspaceId == null || workspaceId.isBlank()) {
                return new AuthorizationDecision(false, "RESOURCE_BINDING_MISSING", decisionId);
            }
            try {
                Long owned = workspaceMapper.selectCount(new LambdaQueryWrapper<ProblemDraftWorkspaceEntity>()
                        .eq(ProblemDraftWorkspaceEntity::getId, workspaceId)
                        .eq(ProblemDraftWorkspaceEntity::getCreatorUserId, context.userId()));
                if (owned == null || owned != 1L) {
                    return new AuthorizationDecision(false, "RESOURCE_SCOPE_DENIED", decisionId);
                }
            } catch (RuntimeException ex) {
                return new AuthorizationDecision(false, "RESOURCE_AUTH_UNAVAILABLE", decisionId);
            }
        }
        return new AuthorizationDecision(true, "ALLOW", decisionId);
    }
}
