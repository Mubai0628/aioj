package com.aioj.next.contract.ai;

import java.util.List;

/**
 * Plan 15.5: what a self-test run found, case by case.
 *
 * @param verdict        AC, WA, TLE, MLE, RE, CE, OLE or SE for the first case that did not pass; OK when
 *                       a custom run had nothing to compare with
 * @param compileOutput  the compiler's message when the code did not compile, capped
 */
public record AiCodeRunResponse(
        String runId,
        String mode,
        String verdict,
        int passed,
        int total,
        String compileOutput,
        List<CaseResult> cases
) {
    public AiCodeRunResponse {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    /**
     * @param stdout           the start of the output, at most 64 KB
     * @param stdoutTruncated  whether the output went on past the preview
     */
    public record CaseResult(
            int index,
            String verdict,
            long timeMillis,
            long memoryKb,
            String stdout,
            boolean stdoutTruncated,
            String stderr,
            FirstDifference firstDifference
    ) {
    }

    /** Where the output first differs from the expected one, as the judge compares them. */
    public record FirstDifference(int line, int column, String expected, String actual) {
    }
}
