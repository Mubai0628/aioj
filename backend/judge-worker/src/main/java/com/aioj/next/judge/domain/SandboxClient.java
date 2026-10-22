package com.aioj.next.judge.domain;

import com.aioj.next.contract.judge.JudgeTaskMessage;
import com.aioj.next.contract.contest.ContestMode;
import com.aioj.next.contract.problem.TestcaseCheckerType;
import com.aioj.next.contract.submission.JudgePhase;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.judge.config.JudgeWorkerProperties;
import com.aioj.next.judge.persistence.entity.SubmissionEntity;
import com.aioj.next.judge.persistence.mapper.SubmissionMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class SandboxClient {
    private static final Logger log = LoggerFactory.getLogger(SandboxClient.class);
    private static final int DEFAULT_STDOUT_BASE_COLLECT_LIMIT_BYTES = 64 * 1024;
    private static final int DEFAULT_STDOUT_EXTRA_COLLECT_BYTES = 64 * 1024;
    private static final int DEFAULT_STDOUT_MAX_COLLECT_LIMIT_BYTES = 4 * 1024 * 1024;
    private static final int DEFAULT_STDERR_COLLECT_LIMIT_BYTES = 64 * 1024;
    private static final long DEFAULT_TIME_LIMIT_MILLIS = 1000L;
    private static final long DEFAULT_MEMORY_LIMIT_KB = 262144L;
    private static final String SANDBOX_ACCEPTED = "Accepted";
    private static final ParameterizedTypeReference<List<SandboxRunResult>> RUN_RESULT_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final JudgeWorkerProperties properties;
    private final TestcasePackageCache testcaseCache;
    private final SubmissionMapper submissionMapper;
    private final OutputComparator outputComparator;
    private final ObjectMapper objectMapper;
    private final SandboxExecutionClient sandboxExecutionClient;
    private final RestClient restClient;

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
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(resolveTimeout(properties.getSandboxTimeout()))
                .build());
        requestFactory.setReadTimeout(resolveTimeout(properties.getSandboxReadTimeout()));
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(stripTrailingPath(properties.getSandboxEndpoint()))
                .requestFactory(requestFactory);
        if (StringUtils.hasText(properties.getSandboxToken())) {
            builder.defaultHeader("Authorization", "Bearer " + properties.getSandboxToken());
        }
        this.restClient = builder.build();
    }

    public JudgeResult judge(JudgeTaskMessage task) {
        if (!properties.getLanguageWhitelist().contains(task.language())) {
            return new JudgeResult(SubmissionStatus.COMPILE_ERROR, "Language is not enabled",
                    0L, 0L, Instant.now(), null, null, null, null)
                    .withDiagnostics(JudgePhase.COMPILE, null, null);
        }
        LangProfile lang;
        try {
            lang = LangProfile.of(task.language());
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

    private CompileOutcome compileSource(LangProfile lang, String sourceCode, long cpuLimitNs, long memoryLimitBytes) {
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
        SandboxRunResult result = runSandbox(List.of(Map.of(
                "args", List.of("/usr/bin/g++", "-O2", "-std=c++17", "checker.cpp", "-o", "checker"),
                "env", List.of("PATH=/usr/bin:/bin"),
                "files", standardFiles("", stdoutBaseCollectLimitBytes(), stderrCollectLimitBytes()),
                "cpuLimit", cpuLimitNs,
                "clockLimit", wallClockLimit(cpuLimitNs),
                "memoryLimit", memoryLimitBytes,
                "procLimit", processLimit(50),
                "copyIn", Map.of("checker.cpp", Map.of("content", checkerSource)),
                "copyOutCached", List.of("checker")
        )));
        Long timeMs = nanosToMillis(result.time());
        Long memoryKb = bytesToKb(result.memory());
        Long runTimeMs = nanosToMillis(result.runTime());
        String stderr = fileContent(result, "stderr");
        if (!"Accepted".equals(result.status())) {
            return CompileOutcome.failed(rawStatus(result), "Custom checker compile failed: "
                            + firstText(stderr, result.error(), result.status()),
                    timeMs, memoryKb, stderr, result.exitStatus(), runTimeMs);
        }
        String fileId = result.fileIds() == null ? null : result.fileIds().get("checker");
        if (!StringUtils.hasText(fileId)) {
            return CompileOutcome.failed("Internal Error",
                    "Custom checker compile succeeded but sandbox did not return cached fileId",
                    timeMs, memoryKb, stderr, result.exitStatus(), runTimeMs);
        }
        return CompileOutcome.success(fileId, timeMs, memoryKb, stderr, result.exitStatus(), runTimeMs);
    }

    private CaseRunOutcome runCase(LangProfile lang,
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
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("args", lang.runArgs());
        command.put("env", lang.envVars());
        command.put("files", standardFiles(stdin, stdoutCollectLimit, stderrCollectLimitBytes()));
        command.put("cpuLimit", cpuLimitNs);
        command.put("clockLimit", wallClockLimit(cpuLimitNs));
        command.put("memoryLimit", memoryLimitBytes);
        command.put("procLimit", processLimit(50));
        Map<String, Object> copyIn = new LinkedHashMap<>();
        if (lang.requiresCompile()) {
            copyIn.put(lang.executableName(), Map.of("fileId", compiledFileId));
        } else {
            copyIn.put(lang.sourceFileName(), Map.of("content", sourceCode));
        }
        command.put("copyIn", copyIn);

        SandboxRunResult result = runSandbox(List.of(command));
        Long timeMs = nanosToMillis(result.time());
        Long memoryKb = bytesToKb(result.memory());
        Long runTimeMs = nanosToMillis(result.runTime());
        String stdout = fileContent(result, "stdout");
        String stderr = fileContent(result, "stderr");
        SubmissionStatus status = mapStatus(result.status(), result.fileError());
        if (status != SubmissionStatus.ACCEPTED) {
            return new CaseRunOutcome(status, firstText(result.error(), result.status()),
                    timeMs, memoryKb, stdout, stderr, result.exitStatus(), runTimeMs, null,
                    JudgePhase.RUN, rawStatus(result));
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
                        JudgePhase.RUN, rawStatus(result));
            }
        }
        return new CaseRunOutcome(SubmissionStatus.ACCEPTED, "Accepted",
                timeMs, memoryKb, stdout, stderr, result.exitStatus(), runTimeMs, null,
                JudgePhase.RUN, rawStatus(result));
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
        Map<String, Object> copyIn = new LinkedHashMap<>();
        copyIn.put("checker", Map.of("fileId", checkerFileId));
        copyIn.put("input.txt", Map.of("content", Files.readString(testcase.inputFile(), StandardCharsets.UTF_8)));
        copyIn.put("actual.out", Map.of("content", nullToEmpty(actualOutput)));
        copyIn.put("expected.out", Map.of("content", Files.readString(testcase.expectedOutputFile(), StandardCharsets.UTF_8)));
        SandboxRunResult result = runSandbox(List.of(Map.of(
                "args", List.of("./checker", "input.txt", "actual.out", "expected.out", maxScore.toPlainString()),
                "env", List.of("PATH=/usr/bin:/bin"),
                "files", standardFiles("", stdoutBaseCollectLimitBytes(), stderrCollectLimitBytes()),
                "cpuLimit", cpuLimitNs,
                "clockLimit", wallClockLimit(cpuLimitNs),
                "memoryLimit", memoryLimitBytes,
                "procLimit", processLimit(50),
                "copyIn", copyIn
        )));
        SubmissionStatus sandboxStatus = mapStatus(result.status(), result.fileError());
        String checkerStdout = fileContent(result, "stdout");
        String checkerStderr = fileContent(result, "stderr");
        if (sandboxStatus != SubmissionStatus.ACCEPTED) {
            return new CaseRunOutcome(SubmissionStatus.SYSTEM_ERROR,
                    "Custom checker execution failed: " + firstText(result.error(), checkerStderr, result.status()),
                    programTimeMs, programMemoryKb, actualOutput, firstText(programStderr, checkerStderr),
                    programExitStatus, programRunTimeMs, BigDecimal.ZERO, JudgePhase.CHECKER, rawStatus(result));
        }
        CheckerJsonResult checkerResult;
        try {
            checkerResult = objectMapper.readValue(nullToEmpty(checkerStdout), CheckerJsonResult.class);
        } catch (JacksonException ex) {
            return new CaseRunOutcome(SubmissionStatus.SYSTEM_ERROR,
                    "Custom checker returned invalid JSON: " + safeMessage(ex),
                    programTimeMs, programMemoryKb, actualOutput, firstText(programStderr, checkerStderr),
                    programExitStatus, programRunTimeMs, BigDecimal.ZERO, JudgePhase.CHECKER, rawStatus(result));
        }
        String checkerStatus = checkerResult.status();
        if (!"ACCEPTED".equals(checkerStatus) && !"WRONG_ANSWER".equals(checkerStatus)) {
            return new CaseRunOutcome(SubmissionStatus.SYSTEM_ERROR,
                    "Custom checker returned unsupported status: " + firstText(checkerStatus),
                    programTimeMs, programMemoryKb, actualOutput, firstText(programStderr, checkerStderr),
                    programExitStatus, programRunTimeMs, BigDecimal.ZERO, JudgePhase.CHECKER, rawStatus(result));
        }
        BigDecimal score = clampScore(checkerResult.score(), maxScore);
        SubmissionStatus terminalStatus = "ACCEPTED".equals(checkerStatus) && isFullScore(score, maxScore)
                ? SubmissionStatus.ACCEPTED
                : SubmissionStatus.WRONG_ANSWER;
        String message = firstText(checkerResult.message(),
                terminalStatus == SubmissionStatus.ACCEPTED ? "Accepted" : "Wrong Answer");
        return new CaseRunOutcome(terminalStatus, message, programTimeMs, programMemoryKb, actualOutput,
                firstText(programStderr, checkerStderr), programExitStatus, programRunTimeMs, score,
                JudgePhase.CHECKER, rawStatus(result));
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

    private SandboxRunResult runSandbox(List<Map<String, Object>> commands) {
        List<SandboxRunResult> results = restClient.post()
                .uri("/run")
                .body(Map.of("cmd", commands))
                .retrieve()
                .body(RUN_RESULT_TYPE);
        if (results == null || results.isEmpty() || results.get(0) == null) {
            throw new IllegalStateException("Sandbox returned empty /run response");
        }
        return results.get(0);
    }

    private void deleteCachedFile(String fileId) {
        restClient.delete().uri("/file/{id}", fileId).retrieve().toBodilessEntity();
    }

    /** The go-judge status of a step; a collect-size file error is reported as the output limit it means. */
    private static String rawStatus(SandboxRunResult result) {
        if (result.fileError() != null) {
            for (SandboxFileError error : result.fileError()) {
                if ("CollectSizeExceeded".equals(error.type())) {
                    return "Output Limit Exceeded";
                }
            }
        }
        return result.status();
    }

    private SubmissionStatus mapStatus(String goJudgeStatus, List<SandboxFileError> fileError) {
        if (fileError != null) {
            for (SandboxFileError error : fileError) {
                if ("CollectSizeExceeded".equals(error.type())) {
                    return SubmissionStatus.OUTPUT_LIMIT_EXCEEDED;
                }
            }
        }
        if (goJudgeStatus == null) {
            return SubmissionStatus.SYSTEM_ERROR;
        }
        return switch (goJudgeStatus) {
            case "Accepted" -> SubmissionStatus.ACCEPTED;
            case "Time Limit Exceeded" -> SubmissionStatus.TIME_LIMIT_EXCEEDED;
            case "Memory Limit Exceeded" -> SubmissionStatus.MEMORY_LIMIT_EXCEEDED;
            case "Output Limit Exceeded" -> SubmissionStatus.OUTPUT_LIMIT_EXCEEDED;
            case "Nonzero Exit Status", "Non Zero Exit Status", "Signalled", "Dangerous Syscall" ->
                    SubmissionStatus.RUNTIME_ERROR;
            case "File Error", "Internal Error" -> SubmissionStatus.SYSTEM_ERROR;
            default -> SubmissionStatus.SYSTEM_ERROR;
        };
    }

    private List<Map<String, Object>> standardFiles(String stdin, int stdoutCollectLimit, int stderrCollectLimit) {
        return List.of(
                Map.of("content", stdin),
                Map.of("name", "stdout", "max", stdoutCollectLimit),
                Map.of("name", "stderr", "max", stderrCollectLimit)
        );
    }

    static int stdoutCollectLimitBytes(long expectedOutputBytes, int baseLimit, int extraBytes, int maxLimit) {
        int normalizedBase = positiveOrDefault(baseLimit, DEFAULT_STDOUT_BASE_COLLECT_LIMIT_BYTES);
        int normalizedExtra = Math.max(0, extraBytes);
        int normalizedMax = Math.max(normalizedBase, positiveOrDefault(maxLimit, DEFAULT_STDOUT_MAX_COLLECT_LIMIT_BYTES));
        long expectedBytes = Math.max(0L, expectedOutputBytes);
        long wanted = expectedBytes <= normalizedBase
                ? normalizedBase
                : safeAdd(expectedBytes, normalizedExtra);
        return (int) Math.min((long) normalizedMax, wanted);
    }

    private int stdoutBaseCollectLimitBytes() {
        return positiveOrDefault(properties.getStdoutBaseCollectLimitBytes(), DEFAULT_STDOUT_BASE_COLLECT_LIMIT_BYTES);
    }

    private int stdoutExtraCollectBytes() {
        return Math.max(0, properties.getStdoutExtraCollectBytes());
    }

    private int stdoutMaxCollectLimitBytes() {
        return positiveOrDefault(properties.getStdoutMaxCollectLimitBytes(), DEFAULT_STDOUT_MAX_COLLECT_LIMIT_BYTES);
    }

    private int stderrCollectLimitBytes() {
        return stderrCollectLimitBytes(properties.getStderrCollectLimitBytes());
    }

    static int stderrCollectLimitBytes(int configuredLimit) {
        return positiveOrDefault(configuredLimit, DEFAULT_STDERR_COLLECT_LIMIT_BYTES);
    }

    private static String stripTrailingPath(String endpoint) {
        if (!StringUtils.hasText(endpoint)) {
            return "http://localhost:8090";
        }
        String trimmed = endpoint.trim();
        if (trimmed.endsWith("/run") || trimmed.endsWith("/execute")) {
            return trimmed.substring(0, trimmed.lastIndexOf('/'));
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static Duration resolveTimeout(Duration timeout) {
        return timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(10) : timeout;
    }

    private static long nonNullOrDefault(Integer value, long fallback) {
        return value == null || value <= 0 ? fallback : value.longValue();
    }

    private static long nonNullOrDefault(Long value, long fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static long millisToNanos(long millis) {
        return safeMultiply(millis, 1_000_000L);
    }

    private static long kbToBytes(long kb) {
        return safeMultiply(kb, 1024L);
    }

    private static long safeMultiply(long value, long factor) {
        if (value > Long.MAX_VALUE / factor) {
            return Long.MAX_VALUE;
        }
        return value * factor;
    }

    private long wallClockLimit(long cpuLimitNs) {
        long normalized = Math.max(1L, cpuLimitNs);
        return Math.max(safeMultiply(normalized, 2L), safeAdd(normalized, millisToNanos(2_000L)));
    }

    private int processLimit(int requested) {
        return Math.min(Math.max(1, requested), positiveOrDefault(properties.getMaxProcessLimit(), 80));
    }

    private static long safeAdd(long value, long addend) {
        if (value > Long.MAX_VALUE - addend) {
            return Long.MAX_VALUE;
        }
        return value + addend;
    }

    private static int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static Long nanosToMillis(Long nanos) {
        if (nanos == null) {
            return null;
        }
        if (nanos <= 0) {
            return 0L;
        }
        return (nanos + 999_999L) / 1_000_000L;
    }

    private static Long bytesToKb(Long bytes) {
        if (bytes == null) {
            return null;
        }
        if (bytes <= 0) {
            return 0L;
        }
        return (bytes + 1023L) / 1024L;
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private static String fileContent(SandboxRunResult result, String name) {
        return result.files() == null ? null : result.files().get(name);
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

    private record LangProfile(
            String language,
            boolean requiresCompile,
            List<String> compileArgs,
            String sourceFileName,
            String executableName,
            List<String> runArgs,
            List<String> envVars
    ) {
        static LangProfile of(String lang) {
            return switch (lang) {
                case "cpp" -> new LangProfile("cpp", true,
                        List.of("/usr/bin/g++", "-O2", "-std=c++17", "main.cpp", "-o", "main"),
                        "main.cpp", "main",
                        List.of("./main"),
                        List.of("PATH=/usr/bin:/bin"));
                case "java" -> new LangProfile("java", true,
                        List.of("/usr/bin/javac", "Main.java"),
                        "Main.java", "Main.class",
                        List.of("/usr/bin/java", "-cp", ".", "Main"),
                        List.of("PATH=/usr/bin:/bin"));
                case "python" -> new LangProfile("python", false,
                        List.of(), "main.py", null,
                        List.of("/usr/bin/python3", "main.py"),
                        List.of("PATH=/usr/bin:/bin"));
                default -> throw new IllegalArgumentException("Unsupported language: " + lang);
            };
        }
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SandboxRunResult(String status,
                                    Integer exitStatus,
                                    Long time,
                                    Long memory,
                                    Long runTime,
                                    Integer procPeak,
                                    Map<String, String> files,
                                    String error,
                                    List<SandboxFileError> fileError,
                                    Map<String, String> fileIds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SandboxFileError(String name, String type, String message) {
    }
}
