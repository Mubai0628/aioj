package com.aioj.next.ai.domain.draftagent;

import com.aioj.next.ai.domain.problem.ProblemDraftDifficulty;
import com.aioj.next.contract.ai.ProblemDraftRequest;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.aioj.next.contract.problem.TestCaseDto;

import java.time.Instant;
import java.util.List;

/** Mutable workspace content. Immutable draft rows are only created by draft.finalize. */
public record ProblemDraftWorkingState(
        String title,
        String difficulty,
        String statement,
        String notes,
        String standardSolutionLanguage,
        String standardSolutionCode,
        String referenceSolutionLanguage,
        String referenceSolutionCode,
        String testcaseGeneratorPython,
        String stressTestcaseGeneratorPython,
        String generationPlan,
        List<String> tags,
        List<TestCaseDto> testCases,
        Integer timeLimitMillis,
        Integer memoryLimitKb
) {
    public ProblemDraftWorkingState {
        tags = tags == null ? List.of() : List.copyOf(tags);
        testCases = testCases == null ? List.of() : List.copyOf(testCases);
    }

    public static ProblemDraftWorkingState fromRequest(ProblemDraftRequest request) {
        String difficulty = request == null ? null
                : ProblemDraftDifficulty.effective(request.difficulty(), request.cfRating());
        String topic = request == null ? null : request.topic();
        return new ProblemDraftWorkingState(
                topic,
                difficulty,
                "",
                "",
                request == null || request.standardSolutionLanguage() == null
                        ? "cpp" : request.standardSolutionLanguage(),
                "",
                null,
                null,
                "",
                null,
                request == null ? "" : safePlan(request),
                request == null || request.tags() == null ? List.of() : request.tags(),
                List.of(),
                2000,
                262144
        );
    }

    public static ProblemDraftWorkingState fromDraft(ProblemDraftResponse draft) {
        return new ProblemDraftWorkingState(draft.title(), draft.difficulty(), draft.statement(), draft.notes(),
                draft.standardSolutionLanguage(), draft.standardSolutionCode(), draft.referenceSolutionLanguage(),
                draft.referenceSolutionCode(), draft.testcaseGeneratorPython(), draft.stressTestcaseGeneratorPython(),
                draft.generationPlan(), draft.tags(), draft.testCases(), draft.timeLimitMillis(), draft.memoryLimitKb());
    }

    public ProblemDraftResponse toResponse(long id, String model, long promptTokens, long completionTokens) {
        return new ProblemDraftResponse(
                id,
                "PENDING_REVIEW",
                title,
                difficulty,
                statement,
                notes,
                standardSolutionLanguage,
                standardSolutionCode,
                referenceSolutionLanguage,
                referenceSolutionCode,
                testcaseGeneratorPython,
                stressTestcaseGeneratorPython,
                generationPlan,
                tags,
                "NOT_RUN",
                List.of(),
                testCases,
                timeLimitMillis,
                memoryLimitKb,
                null,
                model,
                promptTokens,
                completionTokens,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                "NOT_RUN",
                null,
                0,
                null
        );
    }

    private static String safePlan(ProblemDraftRequest request) {
        StringBuilder plan = new StringBuilder();
        append(plan, request.teachingGoal());
        append(plan, request.algorithm());
        append(plan, request.scenario());
        append(plan, request.inputOutputSpec());
        append(plan, request.dataConstraints());
        append(plan, request.qualityRequirements());
        return plan.toString();
    }

    private static void append(StringBuilder target, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!target.isEmpty()) {
            target.append('\n');
        }
        target.append(value.trim());
    }
}
