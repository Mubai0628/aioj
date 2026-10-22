package com.aioj.next.contract.judge;

/**
 * Metadata for the internal, multipart draft testcase package acceptance endpoint.
 * Identity and workspace ownership are deliberately not part of this model; the
 * calling service authorizes those before sending the artifact to judge-worker.
 */
public record DraftTestcasePackageRunRequest(
        String packageSha256,
        Long packageSizeBytes,
        String language,
        String sourceCode,
        String referenceLanguage,
        String referenceSourceCode,
        Integer timeLimitMillis,
        Integer memoryLimitKb,
        Integer maxCases
) {
}
