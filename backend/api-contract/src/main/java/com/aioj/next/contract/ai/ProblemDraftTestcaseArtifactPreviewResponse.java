package com.aioj.next.contract.ai;

import java.util.List;

/**
 * The first entries of a draft's testcase package for review. {@code totalEntries}
 * counts every file; each entry shows at most the first 8 KB, and binary files
 * show no text at all.
 */
public record ProblemDraftTestcaseArtifactPreviewResponse(
        Long artifactId,
        String fileName,
        int totalEntries,
        List<Entry> entries
) {
    public record Entry(
            String name,
            long sizeBytes,
            String preview,
            boolean truncated,
            boolean binary
    ) {
    }
}
