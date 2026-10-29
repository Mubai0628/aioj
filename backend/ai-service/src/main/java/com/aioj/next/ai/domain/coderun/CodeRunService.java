package com.aioj.next.ai.domain.coderun;

import com.aioj.next.agent.harness.sandbox.api.ExecutionFeedback;
import com.aioj.next.agent.harness.sandbox.api.ExecutionFeedbacks;
import com.aioj.next.ai.agent.problem.tool.TutorCodeExecutor;
import com.aioj.next.ai.domain.ProblemServiceClient;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiCodeRunRequest;
import com.aioj.next.contract.ai.AiCodeRunResponse;
import com.aioj.next.contract.ai.AiProblemRunContextRequest;
import com.aioj.next.contract.ai.AiProblemRunContextResponse;
import com.aioj.next.contract.problem.TestCaseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Plan 15.5: the self-test run ("运行"). The editor's code runs on the problem's public samples, or once
 * on an input the user gives, through agent-executor and agent-sandbox, the code tools' path; the judge
 * sandbox only judges. Nothing is stored: no submission, no analysis, no score. Each run leaves one log
 * line, without code, input or output.
 */
@Service
public class CodeRunService {
    static final int MAX_INPUT_BYTES = 1024 * 1024;
    /** agent-executor's limit for source code. */
    static final int MAX_SOURCE_BYTES = 256 * 1024;
    /** agent-executor's limits for one run. */
    static final int MAX_TIME_LIMIT_MILLIS = 30_000;
    static final int MAX_MEMORY_LIMIT_KB = 262_144;
    static final int STDOUT_PREVIEW_CHARS = 64 * 1024;
    static final int STDERR_PREVIEW_CHARS = 4_000;
    static final int COMPILE_OUTPUT_CHARS = 8_000;

    static final String INPUT_TOO_LARGE = "Custom input is larger than 1 MB";
    static final String EXPECTED_TOO_LARGE = "Expected output is larger than 1 MB";
    static final String SOURCE_REQUIRED = "Source code is required";
    static final String SOURCE_TOO_LARGE = "Source code is larger than 256 KB";
    static final String LANGUAGE_UNSUPPORTED = "Code runs support cpp, java and python";
    static final String MODE_UNSUPPORTED = "Code run mode must be SAMPLES or CUSTOM";
    static final String NO_SAMPLES = "This problem has no public samples";
    static final String EXECUTOR_UNAVAILABLE = "The code executor is unavailable; please try again shortly";
    static final String EXECUTOR_REJECTED = "The code executor refused this run";
    static final String PROBLEM_NOT_FOUND = "Problem not found";

    private static final Logger log = LoggerFactory.getLogger(CodeRunService.class);

    private final CodeRunSettingsService settings;
    private final CodeRunGate gate;
    private final ProblemServiceClient problemServiceClient;
    private final TutorCodeExecutor executor;

    public CodeRunService(CodeRunSettingsService settings, CodeRunGate gate, ProblemServiceClient problemServiceClient,
                          TutorCodeExecutor executor) {
        this.settings = settings;
        this.gate = gate;
        this.problemServiceClient = problemServiceClient;
        this.executor = executor;
    }

    public AiCodeRunResponse run(Long userId, AiCodeRunRequest request) {
        if (userId == null) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Unauthorized");
        }
        Checked checked = check(request);
        long started = System.nanoTime();
        try (CodeRunGate.Pass ignored = gate.enter(userId, settings.current(), Instant.now())) {
            AiProblemRunContextResponse problem = problemContext(userId, request, checked.language());
            List<String> inputs = new ArrayList<>();
            List<String> expected = new ArrayList<>();
            if (checked.custom()) {
                inputs.add(checked.input());
                expected.add(checked.expectedOutput());
            } else {
                for (TestCaseDto sample : problem.samples()) {
                    if (sample != null && sample.input() != null) {
                        inputs.add(sample.input());
                        expected.add(sample.expectedOutput());
                    }
                }
                if (inputs.isEmpty()) {
                    throw new DomainException(ErrorCode.BAD_REQUEST, NO_SAMPLES);
                }
            }
            String runId = "run-" + UUID.randomUUID();
            TutorCodeExecutor.Batch batch = executor.run(checked.language(), request.sourceCode(), inputs,
                    limit(problem.timeLimitMillis(), MAX_TIME_LIMIT_MILLIS),
                    limit(problem.memoryLimitKb(), MAX_MEMORY_LIMIT_KB),
                    "user:" + userId, TutorCodeExecutor.RunLimits.SELF_TEST);
            if (batch.failure() != null) {
                log.info("code run runId={} userId={} problemId={} outcome={}", runId, userId, request.problemId(),
                        batch.failure().status());
                throw batch.failure().status() == ExecutionFeedback.Status.REJECTED
                        ? new DomainException(ErrorCode.BAD_REQUEST, EXECUTOR_REJECTED)
                        : new DomainException(ErrorCode.SERVICE_UNAVAILABLE, EXECUTOR_UNAVAILABLE);
            }
            if (batch.runs().size() != inputs.size()) {
                throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, EXECUTOR_UNAVAILABLE);
            }
            AiCodeRunResponse response = response(runId, checked.custom() ? "CUSTOM" : "SAMPLES", batch.runs(),
                    expected);
            log.info("code run runId={} userId={} problemId={} mode={} language={} verdict={} passed={}/{} "
                            + "contestRun={} millis={}", runId, userId, request.problemId(), response.mode(),
                    checked.language(), response.verdict(), response.passed(), response.total(), problem.contestRun(),
                    (System.nanoTime() - started) / 1_000_000);
            return response;
        }
    }

    /** What the request asks for, refused before anything is counted when it cannot run at all. */
    private Checked check(AiCodeRunRequest request) {
        if (request == null || request.problemId() == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Problem id is required");
        }
        String language = language(request.language());
        if (language == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, LANGUAGE_UNSUPPORTED);
        }
        if (request.sourceCode() == null || request.sourceCode().isBlank()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, SOURCE_REQUIRED);
        }
        if (bytes(request.sourceCode()) > MAX_SOURCE_BYTES) {
            throw new DomainException(ErrorCode.PAYLOAD_TOO_LARGE, SOURCE_TOO_LARGE);
        }
        String mode = request.mode() == null || request.mode().isBlank()
                ? "SAMPLES" : request.mode().trim().toUpperCase(Locale.ROOT);
        if (!"SAMPLES".equals(mode) && !"CUSTOM".equals(mode)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, MODE_UNSUPPORTED);
        }
        boolean custom = "CUSTOM".equals(mode);
        String input = custom ? (request.input() == null ? "" : request.input()) : null;
        if (input != null && bytes(input) > MAX_INPUT_BYTES) {
            throw new DomainException(ErrorCode.PAYLOAD_TOO_LARGE, INPUT_TOO_LARGE);
        }
        String expectedOutput = custom ? request.expectedOutput() : null;
        if (expectedOutput != null && bytes(expectedOutput) > MAX_INPUT_BYTES) {
            throw new DomainException(ErrorCode.PAYLOAD_TOO_LARGE, EXPECTED_TOO_LARGE);
        }
        return new Checked(language, custom, input, expectedOutput);
    }

    /** problem-service decides whether this user may see the problem; a miss of any kind is "not found". */
    private AiProblemRunContextResponse problemContext(Long userId, AiCodeRunRequest request, String language) {
        AiProblemRunContextResponse problem;
        try {
            problem = problemServiceClient.aiProblemRunContext(new AiProblemRunContextRequest(userId,
                    request.problemId(), language, request.contestRunId(), request.contestProblemId()));
        } catch (DomainException ex) {
            if (ex.errorCode() == ErrorCode.NOT_FOUND || ex.errorCode() == ErrorCode.FORBIDDEN) {
                throw new DomainException(ErrorCode.NOT_FOUND, PROBLEM_NOT_FOUND);
            }
            throw ex;
        }
        if (problem == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, PROBLEM_NOT_FOUND);
        }
        return problem;
    }

    private static AiCodeRunResponse response(String runId, String mode, List<ExecutionFeedback> runs,
                                              List<String> expected) {
        List<AiCodeRunResponse.CaseResult> cases = new ArrayList<>();
        String overall = null;
        int passed = 0;
        String compileOutput = null;
        for (int i = 0; i < runs.size(); i++) {
            String want = expected.get(i);
            ExecutionFeedback run = runs.get(i).comparedWith(want);
            String verdict = verdict(run);
            if ("AC".equals(verdict) || "OK".equals(verdict)) {
                passed++;
            } else if (overall == null) {
                overall = verdict;
            }
            if (run.status() == ExecutionFeedback.Status.COMPILE_ERROR && compileOutput == null) {
                compileOutput = cap(run.compileOutput(), COMPILE_OUTPUT_CHARS);
            }
            AiCodeRunResponse.FirstDifference difference = null;
            if (run.verdict() == ExecutionFeedback.Verdict.WA) {
                ExecutionFeedbacks.FirstDifference first = ExecutionFeedbacks.firstDifference(i + 1, want, run.stdout());
                if (first != null) {
                    difference = new AiCodeRunResponse.FirstDifference(first.line(), first.column(), first.expected(),
                            first.actual());
                }
            }
            boolean compileFailed = run.status() == ExecutionFeedback.Status.COMPILE_ERROR;
            cases.add(new AiCodeRunResponse.CaseResult(i + 1, verdict, run.cpuMillis(), run.memoryBytes() / 1024L,
                    compileFailed ? "" : cap(run.stdout(), STDOUT_PREVIEW_CHARS),
                    run.outputTruncated() || run.stdout().length() > STDOUT_PREVIEW_CHARS,
                    compileFailed ? "" : cap(run.stderr(), STDERR_PREVIEW_CHARS), difference));
        }
        if (overall == null) {
            overall = cases.stream().allMatch(item -> "OK".equals(item.verdict())) ? "OK" : "AC";
        }
        return new AiCodeRunResponse(runId, mode, overall, passed, cases.size(), compileOutput, cases);
    }

    /** The judge's verdict, or OK for a normal finish that had nothing to be compared with. */
    private static String verdict(ExecutionFeedback run) {
        if (run.verdict() != null) {
            return run.verdict().name();
        }
        return run.status() == ExecutionFeedback.Status.OK ? "OK" : ExecutionFeedback.Verdict.SE.name();
    }

    private static Integer limit(Integer value, int max) {
        return value == null || value <= 0 ? null : Math.min(value, max);
    }

    private static String language(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        return switch (language.strip().toLowerCase(Locale.ROOT)) {
            case "cpp", "c++", "cpp17", "c++17" -> "cpp";
            case "python", "python3", "py" -> "python";
            case "java" -> "java";
            default -> null;
        };
    }

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String cap(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() > maxChars ? value.substring(0, maxChars) : value;
    }

    private record Checked(String language, boolean custom, String input, String expectedOutput) {
    }
}
