package com.aioj.next.contract.ai;

/**
 * Plan 15.5: a self-test run of the editor's code. It makes no submission and is not judged
 * against hidden tests.
 *
 * @param mode             {@code SAMPLES} runs every public sample; {@code CUSTOM} runs {@code input} once
 * @param input            with CUSTOM: the program's stdin, at most 1 MB
 * @param expectedOutput   with CUSTOM, optional: compared like a judge would, at most 1 MB
 * @param contestRunId     with contestProblemId, for a contest problem; the server checks them itself
 */
public record AiCodeRunRequest(
        Long problemId,
        String language,
        String sourceCode,
        String mode,
        String input,
        String expectedOutput,
        Long contestRunId,
        Long contestProblemId
) {
}
