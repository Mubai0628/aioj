package com.aioj.next.contract.problem;

import java.util.List;

/**
 * Search results over public problems. {@code mode} is how they were found;
 * {@code fallback} is set when semantic search was asked for but keyword search
 * answered, with the reason.
 */
public record ProblemSearchResponse(
        List<ProblemSearchHit> items,
        ProblemSearchMode mode,
        boolean fallback,
        ProblemSearchFallbackReason fallbackReason
) {
}
