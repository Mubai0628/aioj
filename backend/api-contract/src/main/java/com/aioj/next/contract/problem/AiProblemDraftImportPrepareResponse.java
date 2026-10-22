package com.aioj.next.contract.problem;

public record AiProblemDraftImportPrepareResponse(
        Long problemId,
        boolean created,
        ProblemVisibility visibility,
        String workspaceId,
        Long draftId
) {
}
