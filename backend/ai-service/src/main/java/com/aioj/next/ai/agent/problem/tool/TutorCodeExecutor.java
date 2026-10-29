package com.aioj.next.ai.agent.problem.tool;

import com.aioj.next.common.http.InternalRestClientFactory;
import com.aioj.next.agent.harness.sandbox.api.ExecutionFeedback;
import com.aioj.next.agent.harness.sandbox.api.ExecutionFeedback.Status;
import com.aioj.next.agent.harness.sandbox.api.ExecutionFeedbacks;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.common.api.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;

/**
 * agent-executor for the tutor's code tools (plan T1.11): compile, or run once per input in one
 * round trip, always with the caller's compile-cache scope (A5.3). It fails closed like the draft
 * port: an executor that cannot be reached is UNAVAILABLE, a refused request is REJECTED, and
 * nothing runs anywhere else.
 */
@Component
public class TutorCodeExecutor {
    static final int DEFAULT_TIME_LIMIT_MILLIS = 2_000;
    static final int MAX_TIME_LIMIT_MILLIS = 5_000;
    static final int DEFAULT_MEMORY_LIMIT_KB = 262_144;
    static final int STDOUT_LIMIT_BYTES = 64 * 1024;

    private static final ParameterizedTypeReference<ApiResponse<CompileResponse>> COMPILE_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<RunBatchResponse>> RUN_BATCH_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    @Autowired
    public TutorCodeExecutor(AiProperties properties, InternalRestClientFactory clients) {
        this(clients.create("agent-executor", stripTrailingSlash(properties.getAgentExecutorUri())));
    }

    TutorCodeExecutor(RestClient restClient) {
        this.restClient = restClient;
    }

    /** What a compile produced, and whether agent-executor answered it from its compile cache. */
    public record Compiled(ExecutionFeedback feedback, boolean cacheHit) {
    }

    /**
     * How far a caller may go: the tutor's tools stop at 5 s and a 64 KB output; the self-test run (plan 15.5)
     * takes the problem's limit up to the executor's 30 s, and up to 4 MiB of output so it can be compared whole.
     */
    public record RunLimits(int maxTimeLimitMillis, int stdoutLimitBytes) {
        public static final RunLimits TUTOR = new RunLimits(MAX_TIME_LIMIT_MILLIS, STDOUT_LIMIT_BYTES);
        public static final RunLimits SELF_TEST = new RunLimits(30_000, 4 * 1024 * 1024);
    }

    /** One run per input, or {@code failure} when the batch did not run at all. */
    public record Batch(List<ExecutionFeedback> runs, ExecutionFeedback failure) {
        public Batch {
            runs = runs == null ? List.of() : List.copyOf(runs);
        }
    }

    public Compiled compile(String language, String source, String cacheScope) {
        CompileResponse response;
        try {
            ApiResponse<CompileResponse> body = restClient.post()
                    .uri("/api/v1/internal/sandbox/compile")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CompileRequest(language, source, null, null, cacheScope))
                    .retrieve()
                    .body(COMPILE_TYPE);
            response = body == null ? null : body.data();
        } catch (HttpClientErrorException ex) {
            return new Compiled(refused(ex), false);
        } catch (RestClientException | IllegalStateException ex) {
            return new Compiled(unavailable(ex), false);
        }
        if (response == null) {
            return new Compiled(unavailable(new IllegalStateException("empty compile response")), false);
        }
        boolean failed = "COMPILE_ERROR".equals(response.status());
        ExecutionFeedback feedback = new ExecutionFeedback(failed ? Status.COMPILE_ERROR : Status.OK, "", "",
                failed ? firstText(response.stderr(), response.message()) : "",
                response.timeMillis() == null ? 0L : response.timeMillis(),
                response.memoryKb() == null ? 0L : response.memoryKb() * 1024L, false)
                .withProcessPeak(response.procPeak() == null ? 0 : response.procPeak());
        return new Compiled(feedback, Boolean.TRUE.equals(response.cacheHit()));
    }

    public Batch run(String language, String source, List<String> inputs, Integer timeLimitMillis,
                     Integer memoryLimitKb, String cacheScope) {
        return run(language, source, inputs, timeLimitMillis, memoryLimitKb, cacheScope, RunLimits.TUTOR);
    }

    public Batch run(String language, String source, List<String> inputs, Integer timeLimitMillis,
                     Integer memoryLimitKb, String cacheScope, RunLimits limits) {
        int time = timeLimitMillis == null || timeLimitMillis <= 0
                ? DEFAULT_TIME_LIMIT_MILLIS : Math.min(limits.maxTimeLimitMillis(), timeLimitMillis);
        int memory = memoryLimitKb == null || memoryLimitKb <= 0
                ? DEFAULT_MEMORY_LIMIT_KB : Math.min(DEFAULT_MEMORY_LIMIT_KB, memoryLimitKb);
        RunBatchResponse response;
        try {
            ApiResponse<RunBatchResponse> body = restClient.post()
                    .uri("/api/v1/internal/sandbox/run-batch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new RunBatchRequest(language, source, null, inputs, time, memory, limits.stdoutLimitBytes(),
                            cacheScope))
                    .retrieve()
                    .body(RUN_BATCH_TYPE);
            response = body == null ? null : body.data();
        } catch (HttpClientErrorException ex) {
            return new Batch(List.of(), refused(ex));
        } catch (RestClientException | IllegalStateException ex) {
            return new Batch(List.of(), unavailable(ex));
        }
        if (response == null || response.results() == null) {
            return new Batch(List.of(), unavailable(new IllegalStateException("empty run response")));
        }
        List<ExecutionFeedback> runs = new ArrayList<>();
        for (RunResponse result : response.results()) {
            runs.add(feedback(result));
        }
        return new Batch(runs, null);
    }

    static ExecutionFeedback feedback(RunResponse result) {
        String code = result.status() == null ? "" : result.status();
        Status status = switch (code) {
            case "ACCEPTED" -> Status.OK;
            case "COMPILE_ERROR" -> Status.COMPILE_ERROR;
            case "RUNTIME_ERROR" -> Status.RUNTIME_ERROR;
            case "TIME_LIMIT_EXCEEDED" -> Status.TIME_LIMIT;
            case "MEMORY_LIMIT_EXCEEDED" -> Status.MEMORY_LIMIT;
            case "OUTPUT_LIMIT_EXCEEDED" -> Status.OUTPUT_LIMIT;
            default -> Status.UNAVAILABLE;
        };
        boolean compileFailed = status == Status.COMPILE_ERROR;
        String stderr = compileFailed ? "" : result.stderr();
        String compileOutput = compileFailed ? firstText(result.stderr(), result.message()) : "";
        long cpu = result.timeMillis() == null ? 0L : result.timeMillis();
        long memory = result.memoryKb() == null ? 0L : result.memoryKb() * 1024L;
        // The executor ran but the sandbox failed the program itself: a system error, not an outage.
        ExecutionFeedback.Verdict verdict = status == Status.UNAVAILABLE ? ExecutionFeedback.Verdict.SE
                : ExecutionFeedbacks.verdictOf(status);
        return new ExecutionFeedback(status, result.stdout(), stderr, compileOutput, cpu, memory,
                status == Status.OUTPUT_LIMIT, verdict, result.procPeak() == null ? 0 : result.procPeak(),
                ExecutionFeedbacks.diagnostics(compileOutput), ExecutionFeedbacks.stderrSummary(stderr), null);
    }

    private static ExecutionFeedback refused(HttpClientErrorException ex) {
        return new ExecutionFeedback(Status.REJECTED, "", firstText(ex.getResponseBodyAsString(), ex.getMessage()),
                "", 0L, 0L, false);
    }

    private static ExecutionFeedback unavailable(Exception ex) {
        return new ExecutionFeedback(Status.UNAVAILABLE, "",
                "agent-executor unavailable: " + firstText(ex.getMessage(), ex.getClass().getSimpleName()), "", 0L, 0L,
                false);
    }

    private static String firstText(String first, String second) {
        return first != null && !first.isBlank() ? first : (second == null ? "" : second);
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    record CompileRequest(String language, String sourceCode, Integer timeLimitMillis, Integer memoryLimitKb,
                          String cacheScope) {
    }

    record CompileResponse(String status, String message, String fileId, String stderr, Long timeMillis,
                           Long memoryKb, Integer exitStatus, Long runTimeMillis, Integer procPeak, Boolean cacheHit) {
    }

    record RunBatchRequest(String language, String sourceCode, String compiledFileId, List<String> inputs,
                           Integer timeLimitMillis, Integer memoryLimitKb, Integer stdoutLimitBytes, String cacheScope) {
    }

    record RunResponse(String status, String message, String stdout, String stderr, Long timeMillis,
                       Long memoryKb, Integer exitStatus, Long runTimeMillis, Integer procPeak) {
    }

    record RunBatchResponse(List<RunResponse> results) {
    }
}
