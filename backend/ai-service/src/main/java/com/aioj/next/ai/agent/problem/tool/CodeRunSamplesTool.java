package com.aioj.next.ai.agent.problem.tool;

import com.aioj.next.agent.harness.sandbox.api.ExecutionFeedback;
import com.aioj.next.agent.harness.sandbox.api.ExecutionFeedbacks;
import com.aioj.next.ai.agent.policy.GuardDecisionRecorder;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolStatus;
import com.aioj.next.ai.domain.ProblemServiceClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in tool {@code code.run_samples} (T1.11): the code against the problem's public samples,
 * in one batch. The samples come through {@code problem.fetch_allowed_view}, so its visibility
 * and contest checks apply; hidden test data is never used.
 */
@Component
public class CodeRunSamplesTool extends TutorCodeTool {
    private final ProblemFetchAllowedViewTool problemView;

    public CodeRunSamplesTool(ProblemServiceClient problemServiceClient, GuardDecisionRecorder guardDecisionRecorder,
                              TutorCodeExecutor executor, ObjectMapper objectMapper,
                              ProblemFetchAllowedViewTool problemView) {
        super(problemServiceClient, guardDecisionRecorder, executor, objectMapper, "code.run_samples",
                "Run code on the current problem's public samples and report each sample's verdict (AC, WA, TLE, "
                        + "MLE, RE, CE, OLE), resources and where the output first differs. The code is one of the "
                        + "user's own submissions (source=submission, its problem) or the code selected in this turn "
                        + "(source=selection, the conversation's problem). Practice only.");
        this.problemView = problemView;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
        Resolution resolution = resolve(context, input);
        if (resolution.refusal() != null) {
            return resolution.refusal();
        }
        CodeSource source = resolution.source();
        if (source.problemId() == null) {
            return ToolResult.failure(null, ToolStatus.NOT_FOUND, "NO_CURRENT_PROBLEM",
                    "this conversation has no problem to take samples from");
        }
        ToolResult<Object> view = problemView.execute(context,
                objectMapper.createObjectNode().put("problemId", source.problemId()));
        if (view.status() != ToolStatus.SUCCESS || !(view.data() instanceof Map<?, ?> problem)) {
            return view;
        }
        List<String> inputs = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        Object samples = problem.get("samples");
        if (samples instanceof List<?> list) {
            for (Object sample : list) {
                if (sample instanceof Map<?, ?> map && map.get("input") != null) {
                    inputs.add(String.valueOf(map.get("input")));
                    expected.add(map.get("expectedOutput") == null ? null : String.valueOf(map.get("expectedOutput")));
                }
            }
        }
        if (inputs.isEmpty()) {
            return ToolResult.failure(null, ToolStatus.NOT_FOUND, "NO_PUBLIC_SAMPLES", "the problem has no public samples");
        }
        String executionId = executionId();
        TutorCodeExecutor.Batch batch = executor.run(source.language(), source.code(), inputs,
                integer(problem.get("timeLimitMillis")), integer(problem.get("memoryLimitKb")), cacheScope(context));
        if (batch.failure() != null) {
            logExecution(context, executionId, String.valueOf(batch.failure().status()));
            return failureOf(batch.failure(), executionId);
        }
        List<Map<String, Object>> cases = new ArrayList<>();
        int passed = 0;
        ExecutionFeedback.Verdict overall = ExecutionFeedback.Verdict.AC;
        List<ExecutionFeedback.CompileDiagnostic> diagnostics = List.of();
        for (int i = 0; i < batch.runs().size(); i++) {
            ExecutionFeedback run = batch.runs().get(i).comparedWith(i < expected.size() ? expected.get(i) : null);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("caseIndex", i + 1);
            node.putAll(feedbackView(run));
            node.remove("diagnostics");
            if (run.verdict() == ExecutionFeedback.Verdict.WA) {
                ExecutionFeedbacks.FirstDifference difference = ExecutionFeedbacks.firstDifference(i + 1, expected.get(i),
                        run.stdout());
                if (difference != null) {
                    node.put("firstDifference", differenceView(difference));
                }
            }
            if (run.verdict() == ExecutionFeedback.Verdict.AC) {
                passed++;
            } else if (overall == ExecutionFeedback.Verdict.AC) {
                overall = run.verdict() == null ? ExecutionFeedback.Verdict.SE : run.verdict();
            }
            if (!run.diagnostics().isEmpty()) {
                diagnostics = run.diagnostics();
            }
            cases.add(node);
        }
        logExecution(context, executionId, overall + " " + passed + "/" + cases.size());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("executionId", executionId);
        data.put("source", source.describe());
        data.put("problemId", String.valueOf(source.problemId()));
        data.put("verdict", overall.name());
        data.put("passed", passed);
        data.put("total", cases.size());
        if (!diagnostics.isEmpty()) {
            data.put("diagnostics", diagnosticsView(diagnostics));
        }
        data.put("cases", cases);
        return success(context, source, data);
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }
}
