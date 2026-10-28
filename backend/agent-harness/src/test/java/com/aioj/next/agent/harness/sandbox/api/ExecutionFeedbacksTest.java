package com.aioj.next.agent.harness.sandbox.api;

import com.aioj.next.agent.harness.sandbox.api.ExecutionFeedback.CompileDiagnostic;
import com.aioj.next.agent.harness.sandbox.api.ExecutionFeedback.Status;
import com.aioj.next.agent.harness.sandbox.api.ExecutionFeedback.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionFeedbacksTest {

    @Test
    void everyStatusMapsToItsVerdict() {
        assertThat(ExecutionFeedbacks.verdictOf(Status.COMPILE_ERROR)).isEqualTo(Verdict.CE);
        assertThat(ExecutionFeedbacks.verdictOf(Status.RUNTIME_ERROR)).isEqualTo(Verdict.RE);
        assertThat(ExecutionFeedbacks.verdictOf(Status.TIME_LIMIT)).isEqualTo(Verdict.TLE);
        assertThat(ExecutionFeedbacks.verdictOf(Status.MEMORY_LIMIT)).isEqualTo(Verdict.MLE);
        assertThat(ExecutionFeedbacks.verdictOf(Status.OUTPUT_LIMIT)).isEqualTo(Verdict.OLE);
        // A normal finish is only AC or WA once compared; refusals and outages carry no verdict.
        assertThat(ExecutionFeedbacks.verdictOf(Status.OK)).isNull();
        assertThat(ExecutionFeedbacks.verdictOf(Status.REJECTED)).isNull();
        assertThat(ExecutionFeedbacks.verdictOf(Status.UNAVAILABLE)).isNull();
    }

    @Test
    void comparingANormalFinishGivesAcOrWa() {
        ExecutionFeedback ran = new ExecutionFeedback(Status.OK, "3 \n\n", "", "", 1, 1, false);

        assertThat(ran.verdict()).isNull();
        assertThat(ran.comparedWith("3").verdict()).isEqualTo(Verdict.AC);
        assertThat(ran.comparedWith("4").verdict()).isEqualTo(Verdict.WA);
        ExecutionFeedback crashed = new ExecutionFeedback(Status.RUNTIME_ERROR, "", "boom", "", 1, 1, false);
        assertThat(crashed.comparedWith("3").verdict()).isEqualTo(Verdict.RE);
    }

    @Test
    void theFirstDifferenceIsLocatedAndCapped() {
        assertThat(ExecutionFeedbacks.firstDifference(0, "1 2 3\n4 5 6\n", "1 2 3  \n4 5 6\n\n")).isNull();

        ExecutionFeedbacks.FirstDifference wrong = ExecutionFeedbacks.firstDifference(2, "1 2 3\n4 5 6\n", "1 2 3\n4 7 6\n");
        assertThat(wrong.caseIndex()).isEqualTo(2);
        assertThat(wrong.line()).isEqualTo(2);
        assertThat(wrong.column()).isEqualTo(3);
        assertThat(wrong.expected()).isEqualTo("4 5 6");
        assertThat(wrong.actual()).isEqualTo("4 7 6");

        ExecutionFeedbacks.FirstDifference shorter = ExecutionFeedbacks.firstDifference(0, "1\n2\n", "1\n");
        assertThat(shorter.line()).isEqualTo(2);
        assertThat(shorter.actual()).isNull();

        String longLine = "x".repeat(500);
        ExecutionFeedbacks.FirstDifference capped = ExecutionFeedbacks.firstDifference(0, longLine + "a", longLine + "b");
        assertThat(capped.column()).isEqualTo(501);
        assertThat(capped.expected().length()).isLessThanOrEqualTo(ExecutionFeedbacks.MAX_SNIPPET_CHARS + 6);
        assertThat(capped.expected()).startsWith("...").endsWith("a");
    }

    @Test
    void compileDiagnosticsAreParsedForEachCompiler() {
        List<CompileDiagnostic> gcc = ExecutionFeedbacks.diagnostics("""
                main.cpp: In function 'int main()':
                main.cpp:4:13: error: expected ';' before 'return'
                main.cpp:7:1: fatal error: missing.h: No such file or directory
                main.cpp:5:3: warning: unused variable 'x'
                """);
        assertThat(gcc).containsExactly(new CompileDiagnostic(4, 13, "expected ';' before 'return'"),
                new CompileDiagnostic(7, 1, "missing.h: No such file or directory"));

        assertThat(ExecutionFeedbacks.diagnostics("Main.java:7: error: cannot find symbol\n  symbol: variable n\n"))
                .containsExactly(new CompileDiagnostic(7, 0, "cannot find symbol"));

        assertThat(ExecutionFeedbacks.diagnostics("""
                  File "main.py", line 3
                    print(x
                         ^
                SyntaxError: '(' was never closed
                """)).containsExactly(new CompileDiagnostic(3, 0, "SyntaxError: '(' was never closed"));

        String many = "main.cpp:1:1: error: e\n".repeat(30);
        assertThat(ExecutionFeedbacks.diagnostics(many)).hasSize(ExecutionFeedbacks.MAX_DIAGNOSTICS);
        assertThat(ExecutionFeedbacks.diagnostics("main.cpp:1:1: error: " + "y".repeat(1000)).get(0).message())
                .hasSize(ExecutionFeedbacks.MAX_DIAGNOSTIC_CHARS + 3);
    }

    @Test
    void theStderrSummaryKeepsTheHeadAndTheLastLine() {
        String trace = """
                Traceback (most recent call last):
                  File "main.py", line 9, in <module>
                    main()
                  File "main.py", line 5, in main
                    print(1 // 0)
                ZeroDivisionError: integer division or modulo by zero
                """;
        assertThat(ExecutionFeedbacks.stderrSummary(trace)).isEqualTo("""
                Traceback (most recent call last):
                File "main.py", line 9, in <module>
                main()
                ...
                ZeroDivisionError: integer division or modulo by zero""");
        assertThat(ExecutionFeedbacks.stderrSummary("  ")).isEmpty();
        assertThat(ExecutionFeedbacks.stderrSummary("z".repeat(2000))).hasSize(ExecutionFeedbacks.MAX_STDERR_SUMMARY_CHARS + 3);
    }

    @Test
    void hiddenCasesGiveOnlyCountsAndResources() {
        ExecutionFeedback fast = new ExecutionFeedback(Status.OK, "secret", "", "", 10, 1024, false).withProcessPeak(1);
        ExecutionFeedback slow = new ExecutionFeedback(Status.TIME_LIMIT, "", "", "", 1000, 4096, false).withProcessPeak(2);
        ExecutionFeedback wrong = new ExecutionFeedback(Status.OK, "other", "", "", 20, 2048, false);

        ExecutionFeedbacks.HiddenCaseSummary summary = ExecutionFeedbacks.summarize(List.of(fast, slow, wrong),
                List.of(Verdict.AC, Verdict.TLE, Verdict.WA));

        assertThat(summary.passed()).isEqualTo(1);
        assertThat(summary.total()).isEqualTo(3);
        assertThat(summary.failures()).isEqualTo(Map.of(Verdict.TLE, 1, Verdict.WA, 1));
        assertThat(summary.maxCpuMillis()).isEqualTo(1000);
        assertThat(summary.maxMemoryBytes()).isEqualTo(4096);
        assertThat(summary.maxProcessPeak()).isEqualTo(2);
        assertThat(summary.toString()).doesNotContain("secret", "other");
    }

    @Test
    void theOldShapeDerivesTheNewFields() {
        ExecutionFeedback compileError = new ExecutionFeedback(Status.COMPILE_ERROR, "", "",
                "main.cpp:2:1: error: 'foo' was not declared", 0, 0, false);

        assertThat(compileError.verdict()).isEqualTo(Verdict.CE);
        assertThat(compileError.diagnostics()).containsExactly(new CompileDiagnostic(2, 1, "'foo' was not declared"));
        assertThat(compileError.withExecutionId("exec-1").executionId()).isEqualTo("exec-1");
    }
}
