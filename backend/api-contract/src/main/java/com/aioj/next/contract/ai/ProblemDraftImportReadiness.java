package com.aioj.next.contract.ai;

import java.util.List;

public record ProblemDraftImportReadiness(
        boolean ready,
        boolean requiresUnverifiedAcknowledgement,
        String verificationMode,
        List<String> blockerCodes,
        List<String> recommendedActions
) {
    public ProblemDraftImportReadiness {
        blockerCodes = blockerCodes == null ? List.of() : List.copyOf(blockerCodes);
        recommendedActions = recommendedActions == null ? List.of() : List.copyOf(recommendedActions);
    }
}
