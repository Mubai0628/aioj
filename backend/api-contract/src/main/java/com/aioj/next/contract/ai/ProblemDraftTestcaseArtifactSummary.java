package com.aioj.next.contract.ai;

import java.time.Instant;

public record ProblemDraftTestcaseArtifactSummary(
        Long id,
        String source,
        String lifecycleStatus,
        String formatStatus,
        String executionStatus,
        String verificationMode,
        String verificationFingerprint,
        boolean stale,
        Integer caseCount,
        Long fileSizeBytes,
        String sha256,
        String failureSummary,
        Long agentRunId,
        Instant generatedAt,
        Instant validatedAt,
        Long importedProblemId,
        Long problemTestcasePackageId
) {
}
