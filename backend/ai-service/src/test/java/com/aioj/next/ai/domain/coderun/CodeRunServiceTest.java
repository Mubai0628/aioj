package com.aioj.next.ai.domain.coderun;

import com.aioj.next.agent.harness.sandbox.api.ExecutionFeedback;
import com.aioj.next.agent.harness.sandbox.api.ExecutionFeedback.Status;
import com.aioj.next.ai.agent.problem.tool.TutorCodeExecutor;
import com.aioj.next.ai.domain.ProblemServiceClient;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiCodeRunRequest;
import com.aioj.next.contract.ai.AiCodeRunResponse;
import com.aioj.next.contract.ai.AiCodeRunSettings;
import com.aioj.next.contract.ai.AiProblemRunContextRequest;
import com.aioj.next.contract.ai.AiProblemRunContextResponse;
import com.aioj.next.contract.problem.TestCaseDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Plan 15.5: the self-test run, on samples or a custom input, with nothing stored. */
class CodeRunServiceTest {
    private static final String CODE = "#include <cstdio>\nint main(){int a,b;scanf(\"%d%d\",&a,&b);printf(\"%d\\n\",a+b);}";

    private final CodeRunSettingsService settings = mock(CodeRunSettingsService.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final CodeRunGate gate = new CodeRunGate(redis);
    private final ProblemServiceClient problems = mock(ProblemServiceClient.class);
    private final TutorCodeExecutor executor = mock(TutorCodeExecutor.class);
    private final CodeRunService service = new CodeRunService(settings, gate, problems, executor);

    @BeforeEach
    void setUp() {
        when(settings.current()).thenReturn(CodeRunSettingsService.DEFAULTS);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(values.increment(anyString())).thenReturn(1L);
    }

    @Test
    void samplesAreJudgedOneByOneWithTheFirstDifference() {
        problem(new AiProblemRunContextResponse(11L, List.of(new TestCaseDto("1 2\n", "3\n", true),
                new TestCaseDto("2 2\n", "4\n", true)), 40_000, 524_288, false));
        runs(ok("3\n", 4, 2048), ok("5\n", 5, 2048));

        AiCodeRunResponse response = service.run(7L, request("SAMPLES", null, null));

        assertThat(response.runId()).startsWith("run-");
        assertThat(response.mode()).isEqualTo("SAMPLES");
        assertThat(response.verdict()).isEqualTo("WA");
        assertThat(response.passed()).isEqualTo(1);
        assertThat(response.total()).isEqualTo(2);
        assertThat(response.cases()).extracting(AiCodeRunResponse.CaseResult::verdict).containsExactly("AC", "WA");
        assertThat(response.cases().get(1).firstDifference())
                .isEqualTo(new AiCodeRunResponse.FirstDifference(1, 1, "4", "5"));
        assertThat(response.cases().get(0).memoryKb()).isEqualTo(2048);
        assertThat(response.cases().get(0).timeMillis()).isEqualTo(4);
        verify(executor).run(eq("cpp"), eq(CODE), eq(List.of("1 2\n", "2 2\n")), eq(30_000), eq(262_144),
                eq("user:7"), eq(TutorCodeExecutor.RunLimits.SELF_TEST));
    }

    @Test
    void aCustomRunWithoutAnExpectedOutputIsOkAndItsOutputIsPreviewed() {
        problem(new AiProblemRunContextResponse(11L, List.of(), 1_000, 262_144, false));
        String big = "9".repeat(CodeRunService.STDOUT_PREVIEW_CHARS + 10);
        runs(ok(big, 3, 1024));

        AiCodeRunResponse response = service.run(7L, request("CUSTOM", "5 4\n", null));

        assertThat(response.verdict()).isEqualTo("OK");
        assertThat(response.passed()).isEqualTo(1);
        AiCodeRunResponse.CaseResult only = response.cases().get(0);
        assertThat(only.verdict()).isEqualTo("OK");
        assertThat(only.stdout()).hasSize(CodeRunService.STDOUT_PREVIEW_CHARS);
        assertThat(only.stdoutTruncated()).isTrue();
        verify(executor).run(eq("cpp"), eq(CODE), eq(List.of("5 4\n")), eq(1_000), eq(262_144), eq("user:7"),
                eq(TutorCodeExecutor.RunLimits.SELF_TEST));
    }

    @Test
    void aCustomRunWithAnExpectedOutputIsCompared() {
        problem(new AiProblemRunContextResponse(11L, List.of(), 1_000, 262_144, false));
        runs(ok("9  \n\n", 3, 1024));

        AiCodeRunResponse response = service.run(7L, request("CUSTOM", "5 4\n", "9\n"));

        assertThat(response.verdict()).as("trailing spaces and blank lines do not count").isEqualTo("AC");
    }

    @Test
    void aCompileErrorIsReportedOnceWithTheCompilersMessage() {
        problem(new AiProblemRunContextResponse(11L, List.of(new TestCaseDto("1 2\n", "3\n", true)), 1_000, 262_144,
                false));
        runs(new ExecutionFeedback(Status.COMPILE_ERROR, "", "", "main.cpp:2:9: error: expected ';'", 0, 0, false));

        AiCodeRunResponse response = service.run(7L, request("SAMPLES", null, null));

        assertThat(response.verdict()).isEqualTo("CE");
        assertThat(response.compileOutput()).contains("expected ';'");
        assertThat(response.cases()).singleElement().satisfies(item -> {
            assertThat(item.verdict()).isEqualTo("CE");
            assertThat(item.stdout()).isEmpty();
        });
    }

    @Test
    void theRunLimitsAndTheVerdictsOfAFailedRunAreTheJudges() {
        problem(new AiProblemRunContextResponse(11L, List.of(new TestCaseDto("1\n", "1\n", true),
                new TestCaseDto("2\n", "2\n", true)), 1_000, 262_144, false));
        runs(new ExecutionFeedback(Status.TIME_LIMIT, "", "", "", 1_000, 1024, false),
                new ExecutionFeedback(Status.RUNTIME_ERROR, "", "Segmentation fault", "", 2, 1024, false));

        AiCodeRunResponse response = service.run(7L, request("SAMPLES", null, null));

        assertThat(response.verdict()).isEqualTo("TLE");
        assertThat(response.cases()).extracting(AiCodeRunResponse.CaseResult::verdict).containsExactly("TLE", "RE");
        assertThat(response.cases().get(1).stderr()).isEqualTo("Segmentation fault");
    }

    @Test
    void anInputOverOneMegabyteIsRefusedBeforeAnythingIsCountedOrRun() {
        assertThatThrownBy(() -> service.run(7L, request("CUSTOM", "x".repeat(CodeRunService.MAX_INPUT_BYTES + 1),
                null)))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
                    assertThat(error.getMessage()).isEqualTo(CodeRunService.INPUT_TOO_LARGE);
                });
        assertThatThrownBy(() -> service.run(7L, request("CUSTOM", "1", "y".repeat(CodeRunService.MAX_INPUT_BYTES + 1))))
                .hasMessage(CodeRunService.EXPECTED_TOO_LARGE);
        assertThatThrownBy(() -> service.run(7L, new AiCodeRunRequest(11L, "cpp",
                "x".repeat(CodeRunService.MAX_SOURCE_BYTES + 1), "SAMPLES", null, null, null, null)))
                .hasMessage(CodeRunService.SOURCE_TOO_LARGE);
        assertThatThrownBy(() -> service.run(7L, new AiCodeRunRequest(11L, "rust", CODE, "SAMPLES", null, null, null,
                null))).hasMessage(CodeRunService.LANGUAGE_UNSUPPORTED);
        assertThatThrownBy(() -> service.run(7L, request("JUDGE", null, null))).hasMessage(CodeRunService.MODE_UNSUPPORTED);
        verifyNoInteractions(redis, problems, executor);
        assertThat(gate.running()).isZero();
    }

    @Test
    void aProblemTheUserCannotSeeIsNotFoundAndItsContestContextIsCheckedByProblemService() {
        when(problems.aiProblemRunContext(any())).thenThrow(new DomainException(ErrorCode.NOT_FOUND,
                "AI problem run context failed: Problem not found"));

        assertThatThrownBy(() -> service.run(7L, new AiCodeRunRequest(11L, "cpp", CODE, "SAMPLES", null, null, 302L,
                401L)))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(error.getMessage()).isEqualTo("Problem not found");
                });
        verify(problems).aiProblemRunContext(new AiProblemRunContextRequest(7L, 11L, "cpp", 302L, 401L));
        verifyNoInteractions(executor);
        assertThat(gate.running()).as("the run's place is given back").isZero();
    }

    @Test
    void aProblemWithoutSamplesNeedsACustomInput() {
        problem(new AiProblemRunContextResponse(11L, List.of(), 1_000, 262_144, false));

        assertThatThrownBy(() -> service.run(7L, request("SAMPLES", null, null))).hasMessage(CodeRunService.NO_SAMPLES);
        verifyNoInteractions(executor);
    }

    @Test
    void anExecutorThatCannotRunItIsReportedPlainly() {
        problem(new AiProblemRunContextResponse(11L, List.of(), 1_000, 262_144, false));
        when(executor.run(anyString(), anyString(), anyList(), any(), any(), anyString(), any()))
                .thenReturn(new TutorCodeExecutor.Batch(List.of(), new ExecutionFeedback(Status.UNAVAILABLE, "",
                        "agent-executor unavailable: 503", "", 0, 0, false)))
                .thenReturn(new TutorCodeExecutor.Batch(List.of(), new ExecutionFeedback(Status.REJECTED, "",
                        "Sandbox stdin exceeds the configured limit", "", 0, 0, false)));

        assertThatThrownBy(() -> service.run(7L, request("CUSTOM", "1", null)))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(error.getMessage()).isEqualTo(CodeRunService.EXECUTOR_UNAVAILABLE);
                });
        assertThatThrownBy(() -> service.run(8L, request("CUSTOM", "1", null)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.getMessage()).isEqualTo(CodeRunService.EXECUTOR_REJECTED));
        assertThat(gate.running()).isZero();
    }

    @Test
    void aRunMakesNoSubmissionAndUsesOnlyTheRunContext() {
        problem(new AiProblemRunContextResponse(11L, List.of(new TestCaseDto("1 2\n", "3\n", true)), 1_000, 262_144,
                false));
        runs(ok("3\n", 1, 1024));

        service.run(7L, request("SAMPLES", null, null));

        verify(problems).aiProblemRunContext(any());
        verifyNoMoreInteractions(problems);
    }

    @Test
    void aSwitchedOffRunnerRunsNothing() {
        when(settings.current()).thenReturn(new AiCodeRunSettings(false, 1, 10, 300, 2_000, null, null));

        assertThatThrownBy(() -> service.run(7L, request("SAMPLES", null, null)))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(error.getMessage()).isEqualTo(CodeRunGate.DISABLED);
                });
        verifyNoInteractions(problems, executor);
    }

    private void problem(AiProblemRunContextResponse context) {
        when(problems.aiProblemRunContext(any())).thenReturn(context);
    }

    private void runs(ExecutionFeedback... feedback) {
        when(executor.run(anyString(), anyString(), anyList(), any(), any(), anyString(), any()))
                .thenReturn(new TutorCodeExecutor.Batch(List.of(feedback), null));
    }

    private static ExecutionFeedback ok(String stdout, long millis, long memoryKb) {
        return new ExecutionFeedback(Status.OK, stdout, "", "", millis, memoryKb * 1024L, false)
                .withProcessPeak(1);
    }

    private static AiCodeRunRequest request(String mode, String input, String expected) {
        return new AiCodeRunRequest(11L, "cpp", CODE, mode, input, expected, null, null);
    }
}
