package com.aioj.next.judge.domain;

import com.aioj.next.contract.judge.JudgeTaskMessage;
import com.aioj.next.contract.contest.ContestMode;
import com.aioj.next.contract.problem.TestcaseCheckerType;
import com.aioj.next.contract.submission.JudgePhase;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.judge.config.JudgeWorkerProperties;
import com.aioj.next.judge.persistence.entity.SubmissionEntity;
import com.aioj.next.judge.persistence.mapper.SubmissionMapper;
import com.aioj.next.sandbox.client.Command;
import com.aioj.next.sandbox.client.GoJudgeClient;
import com.aioj.next.sandbox.client.LanguageProfile;
import com.aioj.next.sandbox.client.Limits;
import com.aioj.next.sandbox.client.OutputComparator;
import com.aioj.next.sandbox.client.ResultCode;
import com.aioj.next.sandbox.client.RunResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.aioj.next.sandbox.client.Limits.bytesToKb;
import static com.aioj.next.sandbox.client.Limits.kbToBytes;
import static com.aioj.next.sandbox.client.Limits.millisToNanos;
import static com.aioj.next.sandbox.client.Limits.nanosToMillis;
import static com.aioj.next.sandbox.client.Limits.positiveOrDefault;
import static com.aioj.next.sandbox.client.Limits.safeMultiply;

@Component
public class SandboxClient {
    private static final Logger log = LoggerFactory.getLogger(SandboxClient.class);
    private static final long DEFAULT_TIME_LIMIT_MILLIS = 1000L;
    private static final long DEFAULT_MEMORY_LIMIT_KB = 262144L;
    private static final String SANDBOX_ACCEPTED = "Accepted";

    private final JudgeWorkerProperties properties;
    private final TestcasePackageCache testcaseCache;
    private final SubmissionMapper submissionMapper;
    private final OutputComparator outputComparator;
    private final ObjectMapper objectMapper;
    private final SandboxExecutionClient sandboxExecutionClient;
    private final GoJudgeClient sandbox;

    public SandboxClient(JudgeWorkerProperties properties,
                         TestcasePackageCache testcaseCache,
                         SubmissionMapper submissionMapper,
                         OutputComparator outputComparator,
                         ObjectMapper objectMapper,
                         SandboxExecutionClient sandboxExecutionClient) {
        this.properties = properties;
        this.testcaseCache = testcaseCache;
        this.submissionMapper = submissionMapper;
        this.outputComparator = outputComparator;
        this.objectMapper = objectMapper;
        this.sandboxExecutionClient = sandboxExecutionClient;
        this.sandbox = new GoJudgeClient(SandboxExecutionClient.sandboxConfig(properties));
    }

    public JudgeResult judge(JudgeTaskMessage task) {
        if (!properties.getLanguageWhitelist().contains(task.language())) {
            return new JudgeResult(SubmissionStatus.COMPILE_ERROR, "Language is not enabled",
                    0L, 0L, Instant.now(), null, null, null, null)
                    .withDiagnostics(JudgePhase.COMPILE, null, null);
        }
        LanguageProfile lang;
        try {
            lang = LanguageProfile.of(task.language());
        } catch (IllegalArgumentException ex) {
            return new JudgeResult(SubmissionStatus.COMPILE_ERROR, ex.getMessage(),
                    0L, 0L, Instant.now(), null, null, null, null)
                    .withDiagnostics(JudgePhase.COMPILE, null, null);
        }

        try {
            SubmissionEntity submission = submissionMapper.selectById(task.submissionId());
            if (submission == null) {
                return JudgeResult.systemError("Submission not found: " + task.submissionId());
            }
            String sourceCode = submission.getCode();
            if (!StringUtils.hasText(sourceCode)) {
                return JudgeResult.systemError("Submission source code is empty");
            }
            if (sourceCode.getBytes(StandardCharsets.UTF_8).length > positiveOrDefault(
                    properties.getMaxSourceBytes(), 256 * 1024)) {
                return new JudgeResult(SubmissionStatus.COMPILE_ERROR, "Submission source code exceeds the limit",
                        0L, 0L, Instant.now(), null, null, null, null)
                        .withDiagnostics(JudgePhase.COMPILE, null, null);
            }

            Optional<PreparedTestcasePackage> preparedPackage = testcaseCache.prepareActivePackage(task.problemId());
            if (preparedPackage.isEmpty()) {
                return JudgeResult.systemError("No active testcase package");
            }
            PreparedTestcasePackage testcasePackage = preparedPackage.get();
            if (testcasePackage.cases().isEmpty()) {
                return JudgeResult.systemError("No testcase available");
            }

            // Every submission keeps the results of the cases it ran. IOI scores all
            // cases; ACM and practice still stop at the first failing case.
            boolean scoreAllCases = task.contestMode() == ContestMode.IOI;
            int caseTotal = testcasePackage.cases().size();
            boolean equalWeight = hasNoCaseScores(testcasePackage.cases());
            long cpuLimitNs = millisToNanos(Math.min(
                    nonNullOrDefault(task.timeLimitMillis(), DEFAULT_TIME_LIMIT_MILLIS),
                    positiveOrDefault(properties.getMaxRunTimeLimitMillis(), 30_000)));
            long memoryLimitBytes = kbToBytes(Math.min(
                    nonNullOrDefault(task.memoryLimitKb(), DEFAULT_MEMORY_LIMIT_KB),
                    positiveOrDefault(properties.getMaxMemoryLimitKb(), 1_048_576)));
            long compileCpuLimitNs = compileCpuLimitNs(cpuLimitNs);
            long sourceCompileMemoryBytes = compileMemoryLimitBytes(memoryLimitBytes, compileMinMemoryKb(lang.language()));
            long checkerCompileMemoryBytes = compileMemoryLimitBytes(memoryLimitBytes,
                    properties.getCheckerCompileMinMemoryKb());
            String compiledFileId = null;
            String checkerFileId = null;
            try {
                if (lang.requiresCompile()) {
                    CompileOutcome compile = compileSource(lang, sourceCode, compileCpuLimitNs,
                            sourceCompileMemoryBytes);
                    if (compile.failed()) {
                        String message = compileFailureMessage(compile);
                        if (scoreAllCases) {
                            List<JudgeCaseResult> caseResults = compileFailureCaseResults(testcasePackage, compile,
                                    SubmissionStatus.COMPILE_ERROR, message, JudgePhase.COMPILE, equalWeight);
                            return new JudgeResult(SubmissionStatus.COMPILE_ERROR, message,
                                    compile.timeMillis(), compile.memoryKb(), Instant.now(),
                                    null, compile.stderr(), compile.exitStatus(), compile.runTimeMillis(),
                                    BigDecimal.ZERO, sumMaxScore(caseResults), caseResults)
                                    .withDiagnostics(JudgePhase.COMPILE, compile.sandboxStatus(), caseTotal);
                        }
                        return new JudgeResult(SubmissionStatus.COMPILE_ERROR, message,
                                compile.timeMillis(), compile.memoryKb(), Instant.now(),
                                null, compile.stderr(), compile.exitStatus(), compile.runTimeMillis())
                                .withDiagnostics(JudgePhase.COMPILE, compile.sandboxStatus(), caseTotal);
                    }
                    compiledFileId = compile.fileId();
                }
                if (testcasePackage.checkerType() == TestcaseCheckerType.CUSTOM) {
                    CompileOutcome checkerCompile = compileChecker(testcasePackage, compileCpuLimitNs,
                            checkerCompileMemoryBytes);
                    if (checkerCompile.failed()) {
                        // A broken checker is the problem setter's fault, never the student's verdict.
                        if (scoreAllCases) {
                            List<JudgeCaseResult> caseResults = compileFailureCaseResults(testcasePackage, checkerCompile,
                                    SubmissionStatus.SYSTEM_ERROR, checkerCompile.message(), JudgePhase.CHECKER,
                                    equalWeight);
                            return new JudgeResult(SubmissionStatus.SYSTEM_ERROR, checkerCompile.message(),
                                    checkerCompile.timeMillis(), checkerCompile.memoryKb(), Instant.now(),
                                    null, checkerCompile.stderr(), checkerCompile.exitStatus(), checkerCompile.runTimeMillis(),
                                    BigDecimal.ZERO, sumMaxScore(caseResults), caseResults)
                                    .withDiagnostics(JudgePhase.CHECKER, checkerCompile.sandboxStatus(), caseTotal);
                        }
                        return JudgeResult.systemError(checkerCompile.message())
                                .withDiagnostics(JudgePhase.CHECKER, checkerCompile.sandboxStatus(), caseTotal);
                    }
                    checkerFileId = checkerCompile.fileId();
                }

                long maxTimeMs = 0L;
                long maxMemoryKb = 0L;
                long maxRunTimeMs = 0L;
                CaseRunOutcome firstFailure = null;
                BigDecimal totalScore = BigDecimal.ZERO;
                BigDecimal totalMaxScore = BigDecimal.ZERO;
                List<JudgeCaseResult> caseResults = new ArrayList<>();
                int caseIndex = 0;
                for (PreparedTestcaseCase testcase : testcasePackage.cases()) {
                    caseIndex++;
                    CaseRunOutcome outcome = runCase(lang, sourceCode, compiledFileId, checkerFileId, testcase, cpuLimitNs,
                            memoryLimitBytes, equalWeight);
                    maxTimeMs = Math.max(maxTimeMs, nullToZero(outcome.timeMillis()));
                    maxMemoryKb = Math.max(maxMemoryKb, nullToZero(outcome.memoryKb()));
                    maxRunTimeMs = Math.max(maxRunTimeMs, nullToZero(outcome.runTimeMillis()));
                    BigDecimal maxScore = caseMaxScore(testcase, equalWeight);
                    BigDecimal score = outcome.score() == null
                            ? (outcome.terminalStatus() == SubmissionStatus.ACCEPTED ? maxScore : BigDecimal.ZERO)
                            : clampScore(outcome.score(), maxScore);
                    totalScore = totalScore.add(score);
                    totalMaxScore = totalMaxScore.add(maxScore);
                    caseResults.add(toCaseResult(testcasePackage, testcase, caseIndex, outcome, score, maxScore));
                    if (outcome.terminalStatus() != SubmissionStatus.ACCEPTED) {
                        if (firstFailure == null) {
                            firstFailure = outcome;
                        }
                        if (!scoreAllCases) {
                            break;
                        }
                    }
                }
                BigDecimal score = scoreAllCases ? totalScore : null;
                BigDecimal maxScore = scoreAllCases ? totalMaxScore : null;
                if (firstFailure != null) {
                    return new JudgeResult(firstFailure.terminalStatus(), firstFailure.message(),
                            firstFailure.timeMillis(), firstFailure.memoryKb(), Instant.now(),
                            firstFailure.stdout(), firstFailure.stderr(), firstFailure.exitStatus(),
                            firstFailure.runTimeMillis(), score, maxScore, caseResults)
                            .withDiagnostics(firstFailure.phase(), firstFailure.sandboxStatus(), caseTotal);
                }
                return new JudgeResult(SubmissionStatus.ACCEPTED, "Accepted",
                        maxTimeMs, maxMemoryKb, Instant.now(), null, null, null, maxRunTimeMs,
                        score, maxScore, caseResults)
                        .withDiagnostics(JudgePhase.RUN, SANDBOX_ACCEPTED, caseTotal);
            } finally {
                if (compiledFileId != null) {
                    try {
                        sandboxExecutionClient.deleteCachedFile(compiledFileId);
                    } catch (RuntimeException ex) {
                        log.warn("Failed to cleanup sandbox fileId={}: {}", compiledFileId, ex.getMessage());
                    }
                }
                if (checkerFileId != null) {
                    try {
                        sandboxExecutionClient.deleteCachedFile(checkerFileId);
                    } catch (RuntimeException ex) {
                        log.warn("Failed to cleanup checker sandbox fileId={}: {}", checkerFileId, ex.getMessage());
                    }
                }
            }
        } catch (RuntimeException | IOException ex) {
            log.warn("Sandbox execution failed for submission={}", task.submissionId(), ex);
            return JudgeResult.systemError("Sandbox execution failed: " + safeMessage(ex));
        }
    }

    long compileCpuLimitNs(long runCpuLimitNs) {
        long multiplier = properties.getCompileTimeMultiplier() > 0 ? properties.getCompileTimeMultiplier() : 10L;
        long capMillis = positiveOrDefault(properties.getMaxScriptTimeLimitMillis(), 180_000);
        if (properties.getCompileMaxTimeMillis() > 0) {
            capMillis = Math.min(capMillis, properties.getCompileMaxTimeMillis());
        }
        return Math.min(safeMultiply(runCpuLimitNs, multiplier), millisToNanos(capMillis));
    }

    long compileMemoryLimitBytes(long runMemoryLimitBytes, long minimumKb) {
        long multiplier = properties.getCompileMemoryMultiplier() > 0 ? properties.getCompileMemoryMultiplier() : 2L;
        long wanted = Math.max(safeMultiply(runMemoryLimitBytes, multiplier), kbToBytes(Math.max(0L, minimumKb)));
        return Math.min(wanted, kbToBytes(positiveOrDefault(properties.getMaxMemoryLimitKb(), 1_048_576)));
    }

    private long compileMinMemoryKb(String language) {
        return switch (language) {
            case "cpp" -> properties.getCppCompileMinMemoryKb();
            case "java" -> properties.getJavaCompileMinMemoryKb();
            default -> 0L;
        };
    }

    /**
     * A compiler that runs out of time or memory is still a compile error for the
     * student; the message names the limit instead of reusing a run verdict.
     */
    static String compileFailureMessage(CompileOutcome compile) {
        String status = compile.sandboxStatus();
        if ("Time Limit Exceeded".equals(status)) {
            return "Compilation exceeded the compile time limit";
        }
        if ("Memory Limit Exceeded".equals(status)) {
            return "Compilation exceeded the compile memory limit";
        }
        if ("Output Limit Exceeded".equals(status)) {
            return "Compiler output exceeded the limit";
        }
        return compile.message();
    }

    /** Legacy packages stored no per-case scores; weigh their cases equally instead of scoring all zero. */
    static boolean hasNoCaseScores(List<PreparedTestcaseCase> cases) {
        return cases.stream().noneMatch(testcase -> testcase.score() != null && testcase.score() > 0);
    }

    private CompileOutcome compileSource(LanguageProfile lang, String sourceCode, long cpuLimitNs, long memoryLimitBytes) {
        SandboxExecutionClient.CompileOutcome outcome = sandboxExecutionClient.compileSource(
                lang.language(), sourceCode, cpuLimitNs, memoryLimitBytes);
        if (outcome.failed()) {
            return CompileOutcome.failed(outcome.sandboxStatus(), outcome.message(), outcome.timeMillis(),
                    outcome.memoryKb(), outcome.stderr(), outcome.exitStatus(), outcome.runTimeMillis());
        }
        return CompileOutcome.success(outcome.fileId(), outcome.timeMillis(), outcome.memoryKb(),
                outcome.stderr(), outcome.exitStatus(), outcome.runTimeMillis());
    }

    private CompileOutcome compileChecker(PreparedTestcasePackage testcasePackage, long cpuLimitNs, long memoryLimitBytes) throws IOException {
        if (testcasePackage.checkerSourceFile() == null) {
            return CompileOutcome.failed(null, "Custom checker source file is missing", 0L, 0L, null, null, null);
        }
        String checkerSource = Files.readString(testcasePackage.checkerSourceFile(), StandardCharsets.UTF_8);
        RunResult result = sandbox.run(Command.builder(
                        List.of("/usr/bin/g++", "-O2", "-std=c++17", "checker.cpp", "-o", "checker"))
                .env(List.of("PATH=/usr/bin:/bin"))
                .standardFiles("", stdoutBaseCollectLimitBytes(), stderrCollectLimitBytes())
                .limits(cpuLimitNs, Limits.wallClockLimit(cpuLimitNs), memoryLimitBytes, processLimit(50))
                .copyInContent("checker.cpp", checkerSource)
                .copyOutCached(List.of("checker"))
                .build());
        Long timeMs = nanosToMillis(result.time());
        Long memoryKb = bytesToKb(result.memory());
        Long runTimeMs = nanosToMillis(result.runTime());
        String stderr = result.file("stderr");
        if (!result.accepted()) {
            return CompileOutcome.failed(result.reportedStatus(), "Custom checker compile failed: "
                            + firstText(stderr, result.error(), result.status()),
                    timeMs, memoryKb, stderr, result.exitStatus(), runTimeMs);
        }
        String fileId = result.cachedFileId("checker");
        if (!StringUtils.hasText(fileId)) {
            return CompileOutcome.failed("Internal Error",
                    "Custom checker compile succeeded but sandbox did not return cached fileId",
                    timeMs, memoryKb, stderr, result.exitStatus(), runTimeMs);
        }
        return CompileOutcome.success(fileId, timeMs, memoryKb, stderr, result.exitStatus(), runTimeMs);
    }

    private CaseRunOutcome runCase(LanguageProfile lang,
                                   String sourceCode,
                                   String compiledFileId,
                                   String checkerFileId,
                                   PreparedTestcaseCase testcase,
                                   long cpuLimitNs,
                                   long memoryLimitBytes,
                                   boolean equalWeight) throws IOException {
        String stdin = Files.readString(testcase.inputFile(), StandardCharsets.UTF_8);
        int stdoutCollectLimit = stdoutCollectLimitBytes(Files.size(testcase.expectedOutputFile()),
                stdoutBaseCollectLimitBytes(), stdoutExtraCollectBytes(), stdoutMaxCollectLimitBytes());
        Command.Builder command = Command.builder(lang.runArgs())
                .env(lang.envVars())
                .standardFiles(stdin, stdoutCollectLimit, stderrCollectLimitBytes())
                .limits(cpuLimitNs, Limits.wallClockLimit(cpuLimitNs), memoryLimitBytes, processLimit(50));
        if (lang.requiresCompile()) {
            command.copyInCached(lang.executableName(), compiledFileId);
        } else {
            command.copyInContent(lang.sourceFileName(), sourceCode);
        }

        RunResult result = sandbox.run(command.build());
        Long timeMs = nanosToMillis(result.time());
        Long memoryKb = bytesToKb(result.memory());
        Long runTimeMs = nanosToMillis(result.runTime());
        String stdout = result.file("stdout");
        String stderr = result.file("stderr");
        SubmissionStatus status = mapStatus(result);
        if (status != SubmissionStatus.ACCEPTED) {
            return new CaseRunOutcome(status, firstText(result.error(), result.status()),
                    timeMs, memoryKb, stdout, stderr, result.exitStatus(), runTimeMs, null,
                    JudgePhase.RUN, result.reportedStatus());
        }
        if (StringUtils.hasText(checkerFileId)) {
            return runChecker(checkerFileId, testcase, stdout, stderr, result.exitStatus(), timeMs, memoryKb,
                    runTimeMs, cpuLimitNs, memoryLimitBytes, equalWeight);
        }
        try (var expected = Files.newInputStream(testcase.expectedOutputFile());
             var actual = new ByteArrayInputStream(nullToEmpty(stdout).getBytes(StandardCharsets.UTF_8))) {
            OutputComparator.ComparisonResult comparison = outputComparator.compare(expected, actual);
            if (!comparison.accepted()) {
                return new CaseRunOutcome(SubmissionStatus.WRONG_ANSWER, comparison.message(),
                        timeMs, memoryKb, stdout, stderr, result.exitStatus(), runTimeMs, null,
                        JudgePhase.RUN, result.reportedStatus());
            }
        }
        return new CaseRunOutcome(SubmissionStatus.ACCEPTED, "Accepted",
                timeMs, memoryKb, stdout, stderr, result.exitStatus(), runTimeMs, null,
                JudgePhase.RUN, result.reportedStatus());
    }

    private CaseRunOutcome runChecker(String checkerFileId,
                                      PreparedTestcaseCase testcase,
                                      String actualOutput,
                                      String programStderr,
                                      Integer programExitStatus,
                                      Long programTimeMs,
                                      Long programMemoryKb,
                                      Long programRunTimeMs,
                                      long cpuLimitNs,
                                      long memoryLimitBytes,
                                      boolean equalWeight) throws IOException {
        BigDecimal maxScore = caseMaxScore(testcase, equalWeight);
        RunResult result = sandbox.run(Command.builder(
                        List.of("./checker", "input.txt", "actual.out", "expected.out", maxScore.toPlainString()))
                .env(List.of("PATH=/usr/bin:/bin"))
                .standardFiles("", stdoutBaseCollectLimitBytes(), stderrCollectLimitBytes())
                .limits(cpuLimitNs, Limits.wallClockLimit(cpuLimitNs), memoryLimitBytes, processLimit(50))
                .copyInCached("checker", checkerFileId)
                .copyInContent("input.txt", Files.readString(testcase.inputFile(), StandardCharsets.UTF_8))
                .copyInContent("actual.out", nullToEmpty(actualOutput))
                .copyInContent("expected.out", Files.readString(testcase.expectedOutputFile(), StandardCharsets.UTF_8))
                .build());
        SubmissionStatus sandboxStatus = mapStatus(result);
        String checkerStdout = result.file("stdout");
        String checkerStderr = result.file("stderr");
        if (sandboxStatus != SubmissionStatus.ACCEPTED) {
            return new CaseRunOutcome(SubmissionStatus.SYSTEM_ERROR,
                    "Custom checker execution failed: " + firstText(result.error(), checkerStderr, result.status()),
                    programTimeMs, programMemoryKb, actualOutput, firstText(programStderr, checkerStderr),
                    programExitStatus, programRunTimeMs, BigDecimal.ZERO, JudgePhase.CHECKER, result.reportedStatus());
        }
        CheckerJsonResult checkerResult;
        try {
            checkerResult = objectMapper.readValue(nullToEmpty(checkerStdout), CheckerJsonResult.class);
        } catch (JacksonException ex) {
            return new CaseRunOutcome(SubmissionStatus.SYSTEM_ERROR,
                    "Custom checker returned invalid JSON: " + safeMessage(ex),
                    programTimeMs, programMemoryKb, actualOutput, firstText(programStderr, checkerStderr),
                    programExitStatus, programRunTimeMs, BigDecimal.ZERO, JudgePhase.CHECKER, result.reportedStatus());
        }
        String checkerStatus = checkerResult.status();
        if (!"ACCEPTED".equals(checkerStatus) && !"WRONG_ANSWER".equals(checkerStatus)) {
            return new CaseRunOutcome(SubmissionStatus.SYSTEM_ERROR,
                    "Custom checker returned unsupported status: " + firstText(checkerStatus),
                    programTimeMs, programMemoryKb, actualOutput, firstText(programStderr, checkerStderr),
                    programExitStatus, programRunTimeMs, BigDecimal.ZERO, JudgePhase.CHECKER, result.reportedStatus());
        }
        BigDecimal score = clampScore(checkerResult.score(), maxScore);
        SubmissionStatus terminalStatus = "ACCEPTED".equals(checkerStatus) && isFullScore(score, maxScore)
                ? SubmissionStatus.ACCEPTED
                : SubmissionStatus.WRONG_ANSWER;
        String message = firstText(checkerResult.message(),
                terminalStatus == SubmissionStatus.ACCEPTED ? "Accepted" : "Wrong Answer");
        return new CaseRunOutcome(terminalStatus, message, programTimeMs, programMemoryKb, actualOutput,
                firstText(programStderr, checkerStderr), programExitStatus, programRunTimeMs, score,
                JudgePhase.CHECKER, result.reportedStatus());
    }

    private List<JudgeCaseResult> compileFailureCaseResults(PreparedTestcasePackage testcasePackage,
                                                            CompileOutcome compile,
                                                            SubmissionStatus status,
                                                            String message,
                                                            JudgePhase phase,
                                                            boolean equalWeight) {
        List<JudgeCaseResult> results = new ArrayList<>();
        int caseIndex = 0;
        for (PreparedTestcaseCase testcase : testcasePackage.cases()) {
            caseIndex++;
            BigDecimal maxScore = caseMaxScore(testcase, equalWeight);
            results.add(new JudgeCaseResult(testcasePackage.packageId(), testcase.id(), caseIndex, testcase.name(),
                    testcase.subtaskKey(), status, BigDecimal.ZERO, maxScore,
                    compile.timeMillis(), compile.memoryKb(), message, phase, compile.sandboxStatus(),
                    testcase.sample()));
        }
        return results;
    }

    private JudgeCaseResult toCaseResult(PreparedTestcasePackage testcasePackage,
                                         PreparedTestcaseCase testcase,
                                         int caseIndex,
                                         CaseRunOutcome outcome,
                                         BigDecimal score,
                                         BigDecimal maxScore) {
        return new JudgeCaseResult(testcasePackage.packageId(), testcase.id(), caseIndex, testcase.name(),
                testcase.subtaskKey(), outcome.terminalStatus(), score, maxScore, outcome.timeMillis(),
                outcome.memoryKb(), outcome.message(), outcome.phase(), outcome.sandboxStatus(), testcase.sample());
    }

    static BigDecimal caseMaxScore(PreparedTestcaseCase testcase, boolean equalWeight) {
        if (equalWeight) {
            return BigDecimal.ONE;
        }
        Integer score = testcase.score();
        if (score == null || score <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(score.longValue());
    }

    private BigDecimal sumMaxScore(List<JudgeCaseResult> caseResults) {
        BigDecimal sum = BigDecimal.ZERO;
        for (JudgeCaseResult result : caseResults) {
            if (result.maxScore() != null) {
                sum = sum.add(result.maxScore());
            }
        }
        return sum;
    }

    private static BigDecimal clampScore(BigDecimal score, BigDecimal maxScore) {
        BigDecimal normalizedScore = score == null ? BigDecimal.ZERO : score;
        BigDecimal normalizedMax = maxScore == null ? BigDecimal.ZERO : maxScore;
        if (normalizedScore.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (normalizedScore.compareTo(normalizedMax) > 0) {
            return normalizedMax;
        }
        return normalizedScore;
    }

    private static boolean isFullScore(BigDecimal score, BigDecimal maxScore) {
        return clampScore(score, maxScore).compareTo(maxScore == null ? BigDecimal.ZERO : maxScore) == 0;
    }

    /** go-judge's status as a verdict; a collector over its cap is the output limit it stands for. */
    private static SubmissionStatus mapStatus(RunResult result) {
        return switch (ResultCode.of(result)) {
            case ACCEPTED -> SubmissionStatus.ACCEPTED;
            case TIME_LIMIT_EXCEEDED -> SubmissionStatus.TIME_LIMIT_EXCEEDED;
            case MEMORY_LIMIT_EXCEEDED -> SubmissionStatus.MEMORY_LIMIT_EXCEEDED;
            case OUTPUT_LIMIT_EXCEEDED -> SubmissionStatus.OUTPUT_LIMIT_EXCEEDED;
            case RUNTIME_ERROR -> SubmissionStatus.RUNTIME_ERROR;
            // go-judge never reports a compile error itself; like any unknown status it is a system error.
            case COMPILE_ERROR, SYSTEM_ERROR -> SubmissionStatus.SYSTEM_ERROR;
        };
    }

    static int stdoutCollectLimitBytes(long expectedOutputBytes, int baseLimit, int extraBytes, int maxLimit) {
        return Limits.stdoutCollectLimitBytes(expectedOutputBytes, baseLimit, extraBytes, maxLimit);
    }

    private int stdoutBaseCollectLimitBytes() {
        return Limits.stdoutBaseCollectLimitBytes(properties.getStdoutBaseCollectLimitBytes());
    }

    private int stdoutExtraCollectBytes() {
        return Math.max(0, properties.getStdoutExtraCollectBytes());
    }

    private int stdoutMaxCollectLimitBytes() {
        return positiveOrDefault(properties.getStdoutMaxCollectLimitBytes(), Limits.DEFAULT_STDOUT_MAX_COLLECT_LIMIT_BYTES);
    }

    private int stderrCollectLimitBytes() {
        return stderrCollectLimitBytes(properties.getStderrCollectLimitBytes());
    }

    static int stderrCollectLimitBytes(int configuredLimit) {
        return Limits.stderrCollectLimitBytes(configuredLimit);
    }

    private static long nonNullOrDefault(Integer value, long fallback) {
        return value == null || value <= 0 ? fallback : value.longValue();
    }

    private static long nonNullOrDefault(Long value, long fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private int processLimit(int requested) {
        return Limits.processLimit(requested, properties.getMaxProcessLimit());
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String firstText(String... values) {
        if (values != null) {
            for (String value : values) {
                if (StringUtils.hasText(value)) {
                    return value;
                }
            }
        }
        return "Sandbox execution failed";
    }

    private static String safeMessage(Exception ex) {
        return StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
    }

    record CompileOutcome(boolean failed,
                          String fileId,
                          String message,
                          Long timeMillis,
                          Long memoryKb,
                          String stderr,
                          Integer exitStatus,
                          Long runTimeMillis,
                          String sandboxStatus) {
        static CompileOutcome success(String fileId, Long timeMillis, Long memoryKb, String stderr,
                                      Integer exitStatus, Long runTimeMillis) {
            return new CompileOutcome(false, fileId, "Accepted", timeMillis, memoryKb, stderr,
                    exitStatus, runTimeMillis, SANDBOX_ACCEPTED);
        }

        static CompileOutcome failed(String sandboxStatus, String message, Long timeMillis, Long memoryKb,
                                     String stderr, Integer exitStatus, Long runTimeMillis) {
            return new CompileOutcome(true, null, message, timeMillis, memoryKb, stderr,
                    exitStatus, runTimeMillis, sandboxStatus);
        }
    }

    private record CaseRunOutcome(SubmissionStatus terminalStatus,
                                  String message,
                                  Long timeMillis,
                                  Long memoryKb,
                                  String stdout,
                                  String stderr,
                                  Integer exitStatus,
                                  Long runTimeMillis,
                                  BigDecimal score,
                                  JudgePhase phase,
                                  String sandboxStatus) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CheckerJsonResult(String status, BigDecimal score, String message) {
    }
}
