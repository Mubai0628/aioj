package com.aioj.next.contract.judge;

import java.util.Map;

/**
 * Safe aggregate returned for hidden draft testcase package acceptance. It never
 * contains testcase input, expected output, program output, sandbox file ids, or
 * filesystem paths.
 */
public record DraftTestcasePackageRunResponse(
        String status,
        String errorCode,
        String message,
        String packageSha256,
        long packageSizeBytes,
        int caseCount,
        int executedCaseCount,
        int passedCaseCount,
        int failedCaseCount,
        Map<String, Integer> failureCounts,
        Long maxTimeMillis,
        Long maxMemoryKb,
        Long maxRunTimeMillis,
        Integer maxProcPeak,
        boolean referenceExecuted,
        int referencePassedCaseCount
) {
}
