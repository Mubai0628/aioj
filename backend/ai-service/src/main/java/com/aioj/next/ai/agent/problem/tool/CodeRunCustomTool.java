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
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Built-in tool {@code code.run_custom} (T1.11): the code on one input the user or the model gives. */
@Component
public class CodeRunCustomTool extends TutorCodeTool {
    static final int MAX_INPUT_BYTES = 16 * 1024;

    public CodeRunCustomTool(ProblemServiceClient problemServiceClient, GuardDecisionRecorder guardDecisionRecorder,
                             TutorCodeExecutor executor, ObjectMapper objectMapper) {
        super(problemServiceClient, guardDecisionRecorder, executor, objectMapper, "code.run_custom",
                "Run code on one input (stdin, up to 16 KB) and return its output, verdict and resources; with "
                        + "expectedOutput it also says AC or WA and where the output first differs. The code is one of "
                        + "the user's own submissions (source=submission), the code selected in this turn "
                        + "(source=selection) or the code in the problem page's editor (source=editor). Use it to "
                        + "check a suspected bug on a small case. Practice only.");
    }

    @Override
    protected void addInputs(ObjectNode properties, List<String> required) {
        ObjectNode stdin = properties.putObject("stdin");
        stdin.put("type", "string");
        stdin.put("maxLength", MAX_INPUT_BYTES);
        stdin.put("description", "The program's standard input.");
        ObjectNode expected = properties.putObject("expectedOutput");
        expected.put("type", "string");
        expected.put("maxLength", MAX_INPUT_BYTES);
        expected.put("description", "Optional: the output the program should print.");
        required.add("stdin");
    }

    @Override
    public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
        String stdin = input.path("stdin").isString() ? input.path("stdin").asString() : null;
        String expected = input.path("expectedOutput").isString() ? input.path("expectedOutput").asString() : null;
        if (stdin == null || bytes(stdin) > MAX_INPUT_BYTES || (expected != null && bytes(expected) > MAX_INPUT_BYTES)) {
            return ToolResult.failure(null, ToolStatus.SCHEMA_ERROR, "INPUT_TOO_LARGE",
                    "stdin is required, and stdin and expectedOutput are 16 KB at most");
        }
        Resolution resolution = resolve(context, input);
        if (resolution.refusal() != null) {
            return resolution.refusal();
        }
        CodeSource source = resolution.source();
        String executionId = executionId();
        TutorCodeExecutor.Batch batch = executor.run(source.language(), source.code(), List.of(stdin), null, null,
                cacheScope(context));
        if (batch.failure() != null) {
            logExecution(context, executionId, String.valueOf(batch.failure().status()));
            return failureOf(batch.failure(), executionId);
        }
        if (batch.runs().isEmpty()) {
            return failureOf(new ExecutionFeedback(ExecutionFeedback.Status.UNAVAILABLE, "", "", "", 0, 0, false),
                    executionId);
        }
        ExecutionFeedback run = batch.runs().get(0).comparedWith(expected).withExecutionId(executionId);
        logExecution(context, executionId, run.status() + "/" + run.verdict());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("executionId", executionId);
        data.put("source", source.describe());
        data.putAll(feedbackView(run));
        data.put("stdout", cap(run.stdout()));
        data.put("stdoutTruncated", run.outputTruncated() || run.stdout().length() > MAX_OUTPUT_CHARS);
        if (run.verdict() == ExecutionFeedback.Verdict.WA) {
            ExecutionFeedbacks.FirstDifference difference = ExecutionFeedbacks.firstDifference(1, expected, run.stdout());
            if (difference != null) {
                data.put("firstDifference", differenceView(difference));
            }
        }
        return success(context, source, data);
    }

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
