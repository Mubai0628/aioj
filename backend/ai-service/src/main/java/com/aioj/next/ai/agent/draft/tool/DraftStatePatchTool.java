package com.aioj.next.ai.agent.draft.tool;

import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.ai.agent.tool.ToolDescriptor;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolRiskLevel;
import com.aioj.next.ai.domain.draftagent.ProblemDraftWorkspaceService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class DraftStatePatchTool implements AgentTool {
    private final ProblemDraftWorkspaceService workspaceService;
    private final ToolDescriptor descriptor;

    public DraftStatePatchTool(ProblemDraftWorkspaceService workspaceService, ObjectMapper mapper) {
        this.workspaceService = workspaceService;
        ObjectNode schema = DraftToolSupport.objectSchema(mapper, false);
        DraftToolSupport.property(schema, "expectedStateVersion", "integer",
                "Optimistic state version returned by draft.state.get.").put("minimum", 0);
        ObjectNode patch = DraftToolSupport.property(schema, "patch", "object",
                "Typed partial draft fields to update. Identity and validation fields are forbidden.");
        patch.put("additionalProperties", false);
        ObjectNode fields = patch.putObject("properties");
        stringField(fields, "title", 255);
        ObjectNode difficulty = stringField(fields, "difficulty", 32);
        difficulty.putArray("enum").add("EASY").add("MEDIUM").add("HARD").add("CHALLENGE");
        stringField(fields, "statement", 262_144,
                "Complete Markdown statement including task, input, output, constraints, and sample explanation.");
        stringField(fields, "notes", 262_144,
                "Teacher-facing solution explanation, correctness argument, complexity, and review notes.");
        languageField(fields, "standardSolutionLanguage");
        stringField(fields, "standardSolutionCode", 262_144,
                "Complete compilable standard solution in the selected supported language.");
        languageField(fields, "referenceSolutionLanguage");
        stringField(fields, "referenceSolutionCode", 262_144,
                "Independent complete reference solution required for high-difficulty package acceptance; use a meaningfully different implementation where practical.");
        stringField(fields, "testcaseGeneratorPython", 262_144,
                "Standalone Python 3 script executed from an isolated writable working directory. It must deterministically create the requested number of non-empty .in files under the current directory or a testcases/ subdirectory. Do not create .out files, a manifest, or a zip; the server materializes expected outputs with the standard solution.");
        stringField(fields, "stressTestcaseGeneratorPython", 262_144,
                "Optional Python 3 differential-test generator. When used it creates small stress_small_*.in/.out pairs; it is not the official package generator.");
        stringField(fields, "generationPlan", 131_072,
                "Concise design plan with core algorithm, constraints, correctness argument, claimed time and memory complexity, and boundary coverage.");

        ObjectNode tags = fields.putObject("tags");
        tags.put("type", "array").put("maxItems", 30);
        tags.putObject("items").put("type", "string").put("maxLength", 64);

        ObjectNode testCases = fields.putObject("testCases");
        testCases.put("type", "array").put("maxItems", 30)
                .put("description", "Public samples. Include at least two representative cases and mark them sample=true.");
        ObjectNode testCase = testCases.putObject("items");
        testCase.put("type", "object").put("additionalProperties", false);
        ObjectNode testCaseFields = testCase.putObject("properties");
        stringField(testCaseFields, "input", 1_048_576);
        stringField(testCaseFields, "expectedOutput", 1_048_576);
        testCaseFields.putObject("sample").put("type", "boolean");
        testCase.putArray("required").add("input").add("expectedOutput");

        fields.putObject("timeLimitMillis").put("type", "integer")
                .put("minimum", 100).put("maximum", 60_000);
        fields.putObject("memoryLimitKb").put("type", "integer")
                .put("minimum", 16_384).put("maximum", 1_048_576);
        DraftToolSupport.require(schema, "expectedStateVersion", "patch");
        this.descriptor = DraftToolSupport.descriptor("draft.state.patch",
                "Patch the current problem state using optimistic concurrency. Changes to verification-relevant fields "
                        + "invalidate prior sample and package acceptance automatically.",
                schema, ToolRiskLevel.MEDIUM, false, false, Duration.ofSeconds(10));
    }

    @Override public ToolDescriptor descriptor() { return descriptor; }

    @Override
    public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
        return DraftToolSupport.success(workspaceService.patch(DraftToolSupport.workspaceId(context), context.userId(),
                input.path("expectedStateVersion").asLong(0L), input.get("patch")));
    }

    private ObjectNode stringField(ObjectNode properties, String name, int maxLength) {
        return properties.putObject(name).put("type", "string").put("maxLength", maxLength);
    }

    private ObjectNode stringField(ObjectNode properties, String name, int maxLength, String description) {
        return stringField(properties, name, maxLength).put("description", description);
    }

    private void languageField(ObjectNode properties, String name) {
        stringField(properties, name, 32).putArray("enum")
                .add("cpp").add("c++").add("c++17").add("cpp17")
                .add("java").add("python").add("py").add("python3");
    }
}
