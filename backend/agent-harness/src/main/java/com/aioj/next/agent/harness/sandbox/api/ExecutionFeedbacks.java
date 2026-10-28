package com.aioj.next.agent.harness.sandbox.api;

import com.aioj.next.agent.harness.sandbox.api.ExecutionFeedback.CompileDiagnostic;
import com.aioj.next.agent.harness.sandbox.api.ExecutionFeedback.Status;
import com.aioj.next.agent.harness.sandbox.api.ExecutionFeedback.Verdict;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A5.1 helpers that turn raw execution output into bounded feedback: verdicts, the first
 * difference against an expected output, located compile diagnostics, a stderr summary, and
 * the aggregate a caller may show for cases whose data must stay hidden.
 */
public final class ExecutionFeedbacks {
    public static final int MAX_DIAGNOSTICS = 10;
    public static final int MAX_DIAGNOSTIC_CHARS = 200;
    public static final int MAX_SNIPPET_CHARS = 80;
    public static final int MAX_STDERR_SUMMARY_CHARS = 400;

    /** gcc / clang: {@code main.cpp:12:5: error: ...}. */
    private static final Pattern GCC = Pattern.compile("^[^:\\n]*:(\\d+):(\\d+):\\s*(?:fatal\\s+)?error:\\s*(.*)$");
    /** javac: {@code Main.java:7: error: ...}. */
    private static final Pattern JAVAC = Pattern.compile("^[^:\\n]*\\.java:(\\d+):\\s*error:\\s*(.*)$");
    /** Python: {@code File "main.py", line 3} followed later by {@code SyntaxError: ...}. */
    private static final Pattern PY_LINE = Pattern.compile("^\\s*File \"[^\"]*\", line (\\d+)");
    private static final Pattern PY_ERROR = Pattern.compile("^(\\w*(?:Error|Exception)):\\s*(.*)$");

    private ExecutionFeedbacks() {
    }

    /** The verdict a status implies on its own; a normal finish has none until it is compared. */
    public static Verdict verdictOf(Status status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case COMPILE_ERROR -> Verdict.CE;
            case RUNTIME_ERROR -> Verdict.RE;
            case TIME_LIMIT -> Verdict.TLE;
            case MEMORY_LIMIT -> Verdict.MLE;
            case OUTPUT_LIMIT -> Verdict.OLE;
            case OK, REJECTED, UNAVAILABLE -> null;
        };
    }

    /**
     * Where two outputs first differ, compared line by line with trailing spaces and trailing
     * empty lines ignored (how a usual checker compares); null when they match.
     */
    public static FirstDifference firstDifference(int caseIndex, String expected, String actual) {
        List<String> want = normalizedLines(expected);
        List<String> got = normalizedLines(actual);
        int lines = Math.max(want.size(), got.size());
        for (int i = 0; i < lines; i++) {
            String wantLine = i < want.size() ? want.get(i) : null;
            String gotLine = i < got.size() ? got.get(i) : null;
            if (wantLine != null && wantLine.equals(gotLine)) {
                continue;
            }
            int column = firstDifferentColumn(wantLine == null ? "" : wantLine, gotLine == null ? "" : gotLine);
            return new FirstDifference(caseIndex, i + 1, column, snippet(wantLine, column), snippet(gotLine, column));
        }
        return null;
    }

    /** Located compiler errors, at most {@link #MAX_DIAGNOSTICS}, each message capped. */
    public static List<CompileDiagnostic> diagnostics(String compileOutput) {
        if (compileOutput == null || compileOutput.isBlank()) {
            return List.of();
        }
        List<CompileDiagnostic> found = new ArrayList<>();
        Integer pythonLine = null;
        for (String raw : compileOutput.split("\\R")) {
            if (found.size() >= MAX_DIAGNOSTICS) {
                break;
            }
            String line = raw.stripTrailing();
            Matcher gcc = GCC.matcher(line);
            if (gcc.matches()) {
                found.add(new CompileDiagnostic(Integer.parseInt(gcc.group(1)), Integer.parseInt(gcc.group(2)),
                        cap(gcc.group(3), MAX_DIAGNOSTIC_CHARS)));
                continue;
            }
            Matcher javac = JAVAC.matcher(line);
            if (javac.matches()) {
                found.add(new CompileDiagnostic(Integer.parseInt(javac.group(1)), 0, cap(javac.group(2), MAX_DIAGNOSTIC_CHARS)));
                continue;
            }
            Matcher pyLine = PY_LINE.matcher(line);
            if (pyLine.find()) {
                pythonLine = Integer.parseInt(pyLine.group(1));
                continue;
            }
            Matcher pyError = PY_ERROR.matcher(line.strip());
            if (pythonLine != null && pyError.matches()) {
                found.add(new CompileDiagnostic(pythonLine, 0,
                        cap(pyError.group(1) + ": " + pyError.group(2), MAX_DIAGNOSTIC_CHARS)));
                pythonLine = null;
            }
        }
        return List.copyOf(found);
    }

    /** The first lines and the last line of stderr, which is where runtimes put the error. */
    public static String stderrSummary(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "";
        }
        List<String> lines = stderr.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        List<String> kept = new ArrayList<>(lines.subList(0, Math.min(3, lines.size())));
        if (lines.size() > 3) {
            if (lines.size() > 4) {
                kept.add("...");
            }
            kept.add(lines.get(lines.size() - 1));
        }
        return cap(String.join("\n", kept), MAX_STDERR_SUMMARY_CHARS);
    }

    /** What may be said about cases whose input and output stay hidden: counts and resources only. */
    public static HiddenCaseSummary summarize(List<ExecutionFeedback> runs, List<Verdict> verdicts) {
        Map<Verdict, Integer> failures = new EnumMap<>(Verdict.class);
        int passed = 0;
        long maxCpu = 0;
        long maxMemory = 0;
        int maxPeak = 0;
        for (int i = 0; i < runs.size(); i++) {
            ExecutionFeedback run = runs.get(i);
            Verdict verdict = i < verdicts.size() ? verdicts.get(i) : run.verdict();
            if (verdict == Verdict.AC) {
                passed++;
            } else {
                failures.merge(verdict == null ? Verdict.SE : verdict, 1, Integer::sum);
            }
            maxCpu = Math.max(maxCpu, run.cpuMillis());
            maxMemory = Math.max(maxMemory, run.memoryBytes());
            maxPeak = Math.max(maxPeak, run.processPeak());
        }
        return new HiddenCaseSummary(passed, runs.size(), failures, maxCpu, maxMemory, maxPeak);
    }

    /** One case's first mismatch; the snippets are capped around the column. */
    public record FirstDifference(int caseIndex, int line, int column, String expected, String actual) {
    }

    /** Hidden cases: how many passed, the failures by verdict and the largest resource use. */
    public record HiddenCaseSummary(int passed, int total, Map<Verdict, Integer> failures, long maxCpuMillis,
                                    long maxMemoryBytes, int maxProcessPeak) {
        public HiddenCaseSummary {
            failures = failures == null ? Map.of() : Map.copyOf(failures);
        }
    }

    private static List<String> normalizedLines(String text) {
        List<String> lines = new ArrayList<>((text == null ? "" : text).lines().map(String::stripTrailing).toList());
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static int firstDifferentColumn(String expected, String actual) {
        int limit = Math.min(expected.length(), actual.length());
        for (int i = 0; i < limit; i++) {
            if (expected.charAt(i) != actual.charAt(i)) {
                return i + 1;
            }
        }
        return limit + 1;
    }

    /** Up to {@link #MAX_SNIPPET_CHARS} characters starting a little before the column; null for a missing line. */
    private static String snippet(String line, int column) {
        if (line == null) {
            return null;
        }
        int start = Math.max(0, Math.min(line.length(), column - 1) - 20);
        int end = Math.min(line.length(), start + MAX_SNIPPET_CHARS);
        return (start > 0 ? "..." : "") + line.substring(start, end) + (end < line.length() ? "..." : "");
    }

    private static String cap(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) + "..." : value;
    }
}
