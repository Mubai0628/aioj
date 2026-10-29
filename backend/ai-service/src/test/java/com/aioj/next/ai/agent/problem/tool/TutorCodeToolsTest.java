package com.aioj.next.ai.agent.problem.tool;

import com.aioj.next.agent.harness.sandbox.api.ExecutionFeedback;
import com.aioj.next.agent.harness.sandbox.api.ExecutionFeedback.Status;
import com.aioj.next.agent.harness.tool.api.ExecutionClass;
import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.agent.context.TrustLevel;
import com.aioj.next.ai.agent.policy.GuardDecisionRecorder;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolStatus;
import com.aioj.next.ai.domain.ProblemServiceClient;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiSubmissionContextRequest;
import com.aioj.next.contract.ai.AiSubmissionContextResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TutorCodeToolsTest {
    private static final String SELECTED = "#include <cstdio>\nint main(){int a,b;scanf(\"%d%d\",&a,&b);printf(\"%d\\n\",a-b);}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProblemServiceClient problems = mock(ProblemServiceClient.class);
    private final GuardDecisionRecorder guard = mock(GuardDecisionRecorder.class);
    private final TutorCodeExecutor executor = mock(TutorCodeExecutor.class);
    private final ProblemFetchAllowedViewTool problemView = mock(ProblemFetchAllowedViewTool.class);
    private final CodeCompileCheckTool compileCheck = new CodeCompileCheckTool(problems, guard, executor, mapper);
    private final CodeRunSamplesTool runSamples = new CodeRunSamplesTool(problems, guard, executor, mapper, problemView);
    private final CodeRunCustomTool runCustom = new CodeRunCustomTool(problems, guard, executor, mapper);

    @Test
    void theToolsAreSandboxedPracticeToolsThatTakeNoCode() {
        for (TutorCodeTool tool : List.of(compileCheck, runSamples, runCustom)) {
            assertThat(tool.descriptor().executionClass()).isEqualTo(ExecutionClass.SANDBOX);
            assertThat(tool.descriptor().budgetCategory()).isEqualTo("sandbox");
            assertThat(tool.descriptor().requiredScopes()).containsExactly("AI_CHAT");
            var properties = tool.descriptor().inputSchema().path("properties");
            assertThat(properties.has("source")).isTrue();
            assertThat(properties.path("source").path("enum").toString())
                    .isEqualTo("[\"submission\",\"selection\",\"editor\"]");
            assertThat(properties.has("code")).isFalse();
            assertThat(properties.has("sourceCode")).isFalse();
            assertThat(tool.descriptor().inputSchema().path("additionalProperties").asBoolean(true)).isFalse();
        }
    }

    @Test
    void compileCheckReadsTheTurnsSelectionAndScopesTheCacheToTheUser() {
        when(executor.compile(eq("cpp"), eq(SELECTED), eq("user:7"))).thenReturn(new TutorCodeExecutor.Compiled(
                new ExecutionFeedback(Status.COMPILE_ERROR, "", "", "main.cpp:2:9: error: expected ';' before '}'",
                        12, 2048, false), true));

        ToolResult<Object> result = compileCheck.execute(context(Map.of("turn.selection", SELECTED)),
                input("selection").put("language", "cpp"));

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        assertThat(result.trustLevel()).isEqualTo(TrustLevel.EXTERNAL_UNTRUSTED);
        assertThat(result.classification()).isEqualTo(DataClassification.USER_PRIVATE);
        Map<String, Object> data = data(result);
        assertThat(data.get("compiled")).isEqualTo(false);
        assertThat(data.get("verdict")).isEqualTo("CE");
        assertThat(data.get("cacheHit")).isEqualTo(true);
        assertThat(String.valueOf(data.get("executionId"))).startsWith("exec-");
        assertThat(data.get("diagnostics").toString()).contains("line=2", "column=9", "expected ';'");
    }

    @Test
    void selectionNeedsASelectionAndItsLanguage() {
        ToolResult<Object> none = compileCheck.execute(context(Map.of()), input("selection").put("language", "cpp"));
        ToolResult<Object> noLanguage = compileCheck.execute(context(Map.of("turn.selection", SELECTED)), input("selection"));

        assertThat(none.status()).isEqualTo(ToolStatus.NOT_FOUND);
        assertThat(none.errorCode()).isEqualTo("NO_SELECTED_CODE");
        assertThat(noLanguage.errorCode()).isEqualTo("LANGUAGE_REQUIRED");
        verifyNoInteractions(executor);
    }

    /** Plan 15.5: the code in the problem page's editor, bound by the turn, with the editor's language. */
    @Test
    void theEditorsCodeRunsInTheEditorsLanguage() {
        when(executor.compile(eq("python"), eq("print(1)"), eq("user:7"))).thenReturn(new TutorCodeExecutor.Compiled(
                new ExecutionFeedback(Status.OK, "", "", "", 3, 1024, false), false));

        ToolResult<Object> result = compileCheck.execute(context(Map.of("turn.editorCode", "print(1)",
                "turn.editorLanguage", "Python3")), input("editor").put("language", "cpp"));

        Map<String, Object> data = data(result);
        assertThat(data.get("compiled")).isEqualTo(true);
        assertThat(data.get("source")).isEqualTo(Map.of("origin", "EDITOR", "language", "python"));
        assertThat(result.sources()).containsExactly(new com.aioj.next.ai.agent.tool.SourceRef("EDITOR", "t-1"));
    }

    @Test
    void theEditorSourceNeedsCodeTheExecutorTakesAndItsLanguage() {
        ToolResult<Object> none = compileCheck.execute(context(Map.of()), input("editor"));
        ToolResult<Object> huge = compileCheck.execute(context(Map.of("turn.editorCode",
                "x".repeat(TutorCodeTool.MAX_EDITOR_CODE_BYTES + 1), "turn.editorLanguage", "cpp")), input("editor"));
        ToolResult<Object> noLanguage = compileCheck.execute(context(Map.of("turn.editorCode", "int main(){}")),
                input("editor"));
        ToolResult<Object> named = runSamples.execute(context(Map.of("turn.editorCode", "int main(){}")),
                input("editor").put("language", "cpp"));

        assertThat(none.status()).isEqualTo(ToolStatus.NOT_FOUND);
        assertThat(none.errorCode()).isEqualTo("NO_EDITOR_CODE");
        assertThat(huge.errorCode()).isEqualTo("EDITOR_CODE_TOO_LARGE");
        assertThat(noLanguage.errorCode()).isEqualTo("LANGUAGE_REQUIRED");
        assertThat(named.errorCode()).as("the language may come from the model, the code may not")
                .isEqualTo("NO_CURRENT_PROBLEM");
        verifyNoInteractions(executor);
    }

    @Test
    void aSubmissionSourceNeedsASubmissionIdOrABoundSubmission() {
        // Found in the 2026-10-29 browser check: a turn opened from the problem page binds no submission.
        ToolResult<Object> none = runSamples.execute(context(Map.of("turn.problemId", "11")), input("submission"));
        ToolResult<Object> bound = compileCheck.execute(context(Map.of("turn.submissionId", "not-an-id")),
                input("submission"));

        assertThat(none.status()).isEqualTo(ToolStatus.SCHEMA_ERROR);
        assertThat(none.errorCode()).isEqualTo("SUBMISSION_ID_REQUIRED");
        assertThat(bound.errorCode()).isEqualTo("SUBMISSION_ID_REQUIRED");
        verifyNoInteractions(executor, problems);
    }

    @Test
    void someoneElsesSubmissionIsRefusedAndAudited() {
        when(problems.aiSubmissionContext(any(AiSubmissionContextRequest.class)))
                .thenThrow(new DomainException(ErrorCode.FORBIDDEN, "not yours"));

        ToolResult<Object> result = runCustom.execute(context(Map.of()),
                input("submission").put("submissionId", 99).put("stdin", "1 2\n"));

        assertThat(result.status()).isEqualTo(ToolStatus.POLICY_DENIED);
        verify(guard).record(any(), any(Long.class), any(), any(), any(), anyList(),
                eq(ProblemToolSupport.REASON_FOREIGN_SUBMISSION_VIEW_DENIED), any(), org.mockito.ArgumentMatchers.anyBoolean(), isNull());
        verifyNoInteractions(executor);
    }

    @Test
    void aSubmissionWhoseCodeIsWithheldFromTheModelIsNotRun() {
        AiSubmissionContextResponse submission = submission(false);
        when(problems.aiSubmissionContext(any(AiSubmissionContextRequest.class))).thenReturn(submission);

        ToolResult<Object> result = compileCheck.execute(context(Map.of("turn.submissionId", "501")), input("submission"));

        assertThat(result.status()).isEqualTo(ToolStatus.POLICY_DENIED);
        assertThat(result.errorCode()).isEqualTo("CODE_NOT_ALLOWED");
        ArgumentCaptor<AiSubmissionContextRequest> request = ArgumentCaptor.forClass(AiSubmissionContextRequest.class);
        verify(problems).aiSubmissionContext(request.capture());
        // The caller's own id and the turn's submission: ownership is problem-service's check.
        assertThat(request.getValue().requestUserId()).isEqualTo(7L);
        assertThat(request.getValue().submissionId()).isEqualTo(501L);
        verifyNoInteractions(executor);
    }

    @Test
    void runSamplesReportsEachSampleAndTheFirstDifference() {
        AiSubmissionContextResponse submission = submission(true);
        when(problems.aiSubmissionContext(any(AiSubmissionContextRequest.class))).thenReturn(submission);
        when(problemView.execute(any(), any())).thenReturn(ToolResult.success(null, Map.of(
                "samples", List.of(Map.of("input", "1 2\n", "expectedOutput", "3\n"),
                        Map.of("input", "5 5\n", "expectedOutput", "10\n")),
                "timeLimitMillis", 1000, "memoryLimitKb", 131072), List.of(), DataClassification.PUBLIC,
                TrustLevel.USER_PROVIDED));
        when(executor.run(eq("cpp"), eq(SELECTED), eq(List.of("1 2\n", "5 5\n")), eq(1000), eq(131072), eq("user:7")))
                .thenReturn(new TutorCodeExecutor.Batch(List.of(
                        new ExecutionFeedback(Status.OK, "3\n", "", "", 1, 1024, false),
                        new ExecutionFeedback(Status.OK, "0\n", "", "", 2, 1024, false)), null));

        ToolResult<Object> result = runSamples.execute(context(Map.of()), input("submission").put("submissionId", 501));

        Map<String, Object> data = data(result);
        assertThat(data.get("verdict")).isEqualTo("WA");
        assertThat(data.get("passed")).isEqualTo(1);
        assertThat(data.get("total")).isEqualTo(2);
        assertThat(data.get("problemId")).isEqualTo("11");
        String cases = data.get("cases").toString();
        assertThat(cases).contains("verdict=AC", "verdict=WA", "firstDifference={line=1, column=1, expected=10, actual=0}");
    }

    @Test
    void runSamplesNeedsAProblemAndUsesTheProblemViewsChecks() {
        ToolResult<Object> noProblem = runSamples.execute(context(Map.of("turn.selection", SELECTED)),
                input("selection").put("language", "cpp"));
        assertThat(noProblem.errorCode()).isEqualTo("NO_CURRENT_PROBLEM");

        ToolResult<Object> denied = ToolResult.failure(null, ToolStatus.POLICY_DENIED, "NO_ACCESSIBLE_PROBLEM", "no");
        when(problemView.execute(any(), any())).thenReturn(denied);
        ToolResult<Object> result = runSamples.execute(context(Map.of("turn.selection", SELECTED, "turn.problemId", "11")),
                input("selection").put("language", "cpp"));

        assertThat(result).isSameAs(denied);
        verifyNoInteractions(executor);
    }

    @Test
    void runCustomComparesWithTheExpectedOutput() {
        when(executor.run(eq("python"), eq("print(int(input())*2)"), eq(List.of("4\n")), isNull(), isNull(), eq("user:7")))
                .thenReturn(new TutorCodeExecutor.Batch(List.of(
                        new ExecutionFeedback(Status.OK, "8\n", "", "", 15, 4096, false)), null));

        ToolResult<Object> result = runCustom.execute(context(Map.of("turn.selection", "print(int(input())*2)")),
                input("selection").put("language", "python").put("stdin", "4\n").put("expectedOutput", "9"));

        Map<String, Object> data = data(result);
        assertThat(data.get("verdict")).isEqualTo("WA");
        assertThat(data.get("stdout")).isEqualTo("8\n");
        assertThat(data.get("firstDifference").toString()).contains("expected=9", "actual=8");
    }

    @Test
    void runCustomRejectsOversizedInputBeforeAnythingRuns() {
        ToolResult<Object> result = runCustom.execute(context(Map.of("turn.selection", SELECTED)),
                input("selection").put("language", "cpp").put("stdin", "x".repeat(CodeRunCustomTool.MAX_INPUT_BYTES + 1)));

        assertThat(result.status()).isEqualTo(ToolStatus.SCHEMA_ERROR);
        verifyNoInteractions(executor, problems);
    }

    @Test
    void anUnavailableExecutorEndsTheCallButNotTheTurn() {
        when(executor.run(anyString(), anyString(), anyList(), any(), any(), anyString()))
                .thenReturn(new TutorCodeExecutor.Batch(List.of(),
                        new ExecutionFeedback(Status.UNAVAILABLE, "", "agent-executor unavailable", "", 0, 0, false)));

        ToolResult<Object> result = runCustom.execute(context(Map.of("turn.selection", SELECTED)),
                input("selection").put("language", "cpp").put("stdin", "1 2\n"));

        assertThat(result.status()).isEqualTo(ToolStatus.SERVICE_UNAVAILABLE);
        assertThat(result.errorCode()).isEqualTo("EXECUTOR_UNAVAILABLE");
        assertThat(result.errorMessage()).startsWith("UNAVAILABLE");
        verify(problems, never()).aiSubmissionContext(any());
    }

    private AiSubmissionContextResponse submission(boolean codeAllowed) {
        AiSubmissionContextResponse submission = mock(AiSubmissionContextResponse.class);
        when(submission.codeAllowedToModel()).thenReturn(codeAllowed);
        when(submission.codeText()).thenReturn(SELECTED);
        when(submission.language()).thenReturn("C++");
        when(submission.problemId()).thenReturn(11L);
        return submission;
    }

    private ObjectNode input(String source) {
        return mapper.createObjectNode().put("source", source);
    }

    private static ToolExecutionContext context(Map<String, String> bindings) {
        return new ToolExecutionContext(7L, "c-1", "t-1", 1L, "snap-1", Set.of("AI_CHAT"), Instant.now(), "trace-1",
                null, bindings);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResult<Object> result) {
        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        return (Map<String, Object>) result.data();
    }
}
