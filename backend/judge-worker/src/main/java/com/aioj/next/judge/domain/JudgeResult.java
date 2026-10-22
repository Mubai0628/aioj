package com.aioj.next.judge.domain;

import com.aioj.next.contract.submission.JudgePhase;
import com.aioj.next.contract.submission.SubmissionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Terminal judge outcome. {@code phase} and {@code sandboxStatus} say where the
 * verdict came from (the raw go-judge status for that step); {@code caseTotal}
 * is the testcase count of the package, which can exceed {@code caseResults}
 * when judging stopped at the first failing case.
 */
public record JudgeResult(
        SubmissionStatus status,
        String message,
        Long timeMillis,
        Long memoryKb,
        Instant judgedAt,
        String stdout,
        String stderr,
        Integer exitStatus,
        Long runTimeMillis,
        BigDecimal score,
        BigDecimal maxScore,
        List<JudgeCaseResult> caseResults,
        JudgePhase phase,
        String sandboxStatus,
        Integer caseTotal
) {
    public JudgeResult(SubmissionStatus status,
                       String message,
                       Long timeMillis,
                       Long memoryKb,
                       Instant judgedAt,
                       String stdout,
                       String stderr,
                       Integer exitStatus,
                       Long runTimeMillis,
                       BigDecimal score,
                       BigDecimal maxScore,
                       List<JudgeCaseResult> caseResults) {
        this(status, message, timeMillis, memoryKb, judgedAt, stdout, stderr, exitStatus, runTimeMillis,
                score, maxScore, caseResults, null, null, null);
    }

    public JudgeResult(SubmissionStatus status,
                       String message,
                       Long timeMillis,
                       Long memoryKb,
                       Instant judgedAt,
                       String stdout,
                       String stderr,
                       Integer exitStatus,
                       Long runTimeMillis) {
        this(status, message, timeMillis, memoryKb, judgedAt, stdout, stderr, exitStatus, runTimeMillis,
                null, null, List.of());
    }

    public static JudgeResult systemError(String message) {
        return new JudgeResult(SubmissionStatus.SYSTEM_ERROR, message,
                0L, 0L, Instant.now(), null, null, null, null);
    }

    public JudgeResult withDiagnostics(JudgePhase phase, String sandboxStatus, Integer caseTotal) {
        return new JudgeResult(status, message, timeMillis, memoryKb, judgedAt, stdout, stderr, exitStatus,
                runTimeMillis, score, maxScore, caseResults, phase, sandboxStatus, caseTotal);
    }
}
