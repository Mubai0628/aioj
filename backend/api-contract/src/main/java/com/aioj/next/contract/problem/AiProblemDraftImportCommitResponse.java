package com.aioj.next.contract.problem;

public record AiProblemDraftImportCommitResponse(
        Long problemId,
        Long activeTestcasePackageId,
        ProblemVisibility visibility,
        String workspaceId,
        Long draftId,
        boolean aiGenerated,
        String reviewRisk,
        String testcaseVerificationMode
) {
}
