package com.aioj.next.ai.domain.draftagent;

import com.aioj.next.contract.ai.ProblemDraftReviewRisk;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class ProblemDraftReviewRiskService {
    public static final String HIGH_DIFFICULTY_MESSAGE =
            "该题由 AI 生成且难度较高，题意、复杂度、标程与测试覆盖仍需人工仔细甄别。";
    public static final String MANUAL_UNVERIFIED_MESSAGE =
            "该题测试包未经 Agent 执行验收，导入前必须人工核对题意、标程、测试覆盖和资源限制。";

    public ProblemDraftReviewRisk derive(String difficulty, Integer requestedCfRating, boolean manualUnverified) {
        boolean high = requestedCfRating != null && requestedCfRating >= 1701;
        if (difficulty != null) {
            String normalized = difficulty.trim().toUpperCase(Locale.ROOT);
            high = high || "HARD".equals(normalized) || "CHALLENGE".equals(normalized);
        }
        if (manualUnverified) {
            return new ProblemDraftReviewRisk("MANUAL_UNVERIFIED", "CRITICAL", high, true,
                    MANUAL_UNVERIFIED_MESSAGE);
        }
        if (high) {
            return new ProblemDraftReviewRisk("HIGH_DIFFICULTY_AI", "WARNING", true, false,
                    HIGH_DIFFICULTY_MESSAGE);
        }
        return new ProblemDraftReviewRisk("STANDARD", "INFO", false, false, null);
    }

    public ProblemDraftReviewRisk fromCode(String code) {
        if ("MANUAL_UNVERIFIED".equalsIgnoreCase(code)) {
            return new ProblemDraftReviewRisk("MANUAL_UNVERIFIED", "CRITICAL", false, true,
                    MANUAL_UNVERIFIED_MESSAGE);
        }
        if ("HIGH_DIFFICULTY_AI".equalsIgnoreCase(code)) {
            return new ProblemDraftReviewRisk("HIGH_DIFFICULTY_AI", "WARNING", true, false,
                    HIGH_DIFFICULTY_MESSAGE);
        }
        return new ProblemDraftReviewRisk("STANDARD", "INFO", false, false, null);
    }
}
