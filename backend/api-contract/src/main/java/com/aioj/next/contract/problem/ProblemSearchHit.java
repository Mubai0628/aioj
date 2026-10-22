package com.aioj.next.contract.problem;

import java.util.List;

public record ProblemSearchHit(
        Long problemId,
        String title,
        Difficulty difficulty,
        List<String> tags,
        double score
) {
}
