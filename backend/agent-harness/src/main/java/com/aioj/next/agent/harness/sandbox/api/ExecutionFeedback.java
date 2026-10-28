package com.aioj.next.agent.harness.sandbox.api;

import java.util.List;

/**
 * What one execution produced, already cut to the request's output limit.
 * {@code UNAVAILABLE} means the executor could not run the program at all.
 *
 * <p>A5.1 adds what a tool needs to say why a run went wrong without the model reading raw
 * output: the {@link Verdict}, the process peak, compile diagnostics with line numbers, a short
 * stderr summary and an {@code executionId} the run can be looked up by. The verdict is null when
 * nothing was compared (a run without an expected output that finished normally).</p>
 */
public record ExecutionFeedback(Status status, String stdout, String stderr, String compileOutput, long cpuMillis,
                                long memoryBytes, boolean outputTruncated, Verdict verdict, int processPeak,
                                List<CompileDiagnostic> diagnostics, String stderrSummary, String executionId) {
    public enum Status {
        OK,
        COMPILE_ERROR,
        RUNTIME_ERROR,
        TIME_LIMIT,
        MEMORY_LIMIT,
        OUTPUT_LIMIT,
        REJECTED,
        UNAVAILABLE
    }

    /** The usual verdicts; SE is a sandbox-side failure while running the program. */
    public enum Verdict {
        AC,
        WA,
        TLE,
        MLE,
        RE,
        CE,
        OLE,
        SE
    }

    /** One compiler message, located; {@code column} is 0 when the compiler gives none. */
    public record CompileDiagnostic(int line, int column, String message) {
        public CompileDiagnostic {
            message = message == null ? "" : message;
        }
    }

    public ExecutionFeedback {
        status = status == null ? Status.UNAVAILABLE : status;
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
        compileOutput = compileOutput == null ? "" : compileOutput;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        stderrSummary = stderrSummary == null ? "" : stderrSummary;
    }

    /** The pre-A5.1 shape: verdict, diagnostics and stderr summary are derived from it. */
    public ExecutionFeedback(Status status, String stdout, String stderr, String compileOutput, long cpuMillis,
                             long memoryBytes, boolean outputTruncated) {
        this(status, stdout, stderr, compileOutput, cpuMillis, memoryBytes, outputTruncated,
                ExecutionFeedbacks.verdictOf(status), 0, ExecutionFeedbacks.diagnostics(compileOutput),
                ExecutionFeedbacks.stderrSummary(stderr), null);
    }

    public ExecutionFeedback withExecutionId(String id) {
        return new ExecutionFeedback(status, stdout, stderr, compileOutput, cpuMillis, memoryBytes, outputTruncated,
                verdict, processPeak, diagnostics, stderrSummary, id);
    }

    public ExecutionFeedback withProcessPeak(int peak) {
        return new ExecutionFeedback(status, stdout, stderr, compileOutput, cpuMillis, memoryBytes, outputTruncated,
                verdict, Math.max(0, peak), diagnostics, stderrSummary, executionId);
    }

    /** The verdict after comparing stdout with an expected output (only a normal finish is compared). */
    public ExecutionFeedback comparedWith(String expectedOutput) {
        if (status != Status.OK || expectedOutput == null) {
            return this;
        }
        Verdict compared = ExecutionFeedbacks.firstDifference(0, expectedOutput, stdout) == null ? Verdict.AC : Verdict.WA;
        return new ExecutionFeedback(status, stdout, stderr, compileOutput, cpuMillis, memoryBytes, outputTruncated,
                compared, processPeak, diagnostics, stderrSummary, executionId);
    }
}
