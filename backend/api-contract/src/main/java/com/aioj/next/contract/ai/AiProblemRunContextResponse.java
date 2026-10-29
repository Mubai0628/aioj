package com.aioj.next.contract.ai;

import com.aioj.next.contract.problem.TestCaseDto;

import java.util.List;

/**
 * Plan 15.5: the public samples and the limits a self-test run uses; never a hidden test.
 *
 * @param timeLimitMillis  the problem's limit for the run's language, as the judge applies it
 * @param contestRun       whether access came from an active contest run (a private problem)
 */
public record AiProblemRunContextResponse(
        Long problemId,
        List<TestCaseDto> samples,
        Integer timeLimitMillis,
        Integer memoryLimitKb,
        boolean contestRun
) {
    public AiProblemRunContextResponse {
        samples = samples == null ? List.of() : List.copyOf(samples);
    }
}
