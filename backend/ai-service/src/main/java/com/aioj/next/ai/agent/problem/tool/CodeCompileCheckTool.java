package com.aioj.next.ai.agent.problem.tool;

import com.aioj.next.agent.harness.sandbox.api.ExecutionFeedback;
import com.aioj.next.ai.agent.policy.GuardDecisionRecorder;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.domain.ProblemServiceClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Built-in tool {@code code.compile_check} (T1.11): compile the code, return located diagnostics. */
@Component
public class CodeCompileCheckTool extends TutorCodeTool {

    public CodeCompileCheckTool(ProblemServiceClient problemServiceClient, GuardDecisionRecorder guardDecisionRecorder,
                                TutorCodeExecutor executor, ObjectMapper objectMapper) {
        super(problemServiceClient, guardDecisionRecorder, executor, objectMapper, "code.compile_check",
                "Compile code and return the compiler's errors with line numbers. The code is one of the user's own "
                        + "submissions (source=submission), the code the user selected in this turn "
                        + "(source=selection) or the code in the problem page's editor (source=editor). Use it when "
                        + "the user asks why code does not compile. Practice only.");
    }

    @Override
    public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
        Resolution resolution = resolve(context, input);
        if (resolution.refusal() != null) {
            return resolution.refusal();
        }
        CodeSource source = resolution.source();
        String executionId = executionId();
        TutorCodeExecutor.Compiled compiled = executor.compile(source.language(), source.code(), cacheScope(context));
        ExecutionFeedback feedback = compiled.feedback().withExecutionId(executionId);
        logExecution(context, executionId, feedback.status() + "/" + feedback.verdict() + " cacheHit=" + compiled.cacheHit());
        if (feedback.status() == ExecutionFeedback.Status.UNAVAILABLE
                || feedback.status() == ExecutionFeedback.Status.REJECTED) {
            return failureOf(feedback, executionId);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("executionId", executionId);
        data.put("source", source.describe());
        data.put("compiled", feedback.status() == ExecutionFeedback.Status.OK);
        data.putAll(feedbackView(feedback));
        if (feedback.status() == ExecutionFeedback.Status.COMPILE_ERROR) {
            data.put("compileOutput", cap(feedback.compileOutput()));
        }
        data.put("cacheHit", compiled.cacheHit());
        return success(context, source, data);
    }
}
