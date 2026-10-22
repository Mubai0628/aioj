package com.aioj.next.contract.problem;

import java.util.List;

public record ProblemSemanticSearchResponse(
        List<ProblemSemanticSearchRecord> records,
        boolean fallback
) {
}