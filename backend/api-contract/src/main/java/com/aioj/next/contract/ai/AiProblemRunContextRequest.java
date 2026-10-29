package com.aioj.next.contract.ai;

/**
 * Plan 15.5: what a self-test run of one user may use from one problem. problem-service checks the
 * contest context itself; the caller's word for it is not taken.
 */
public record AiProblemRunContextRequest(
        Long requestUserId,
        Long problemId,
        String language,
        Long contestRunId,
        Long contestProblemId
) {
}
