package com.aioj.next.contract.ai;

public record ProblemDraftAgentTurnResponse(
        String workspaceId,
        String turnId,
        Long generationJobId,
        Long agentRunId,
        Long currentDraftId,
        String status,
        boolean idempotentReplay
) {
}
