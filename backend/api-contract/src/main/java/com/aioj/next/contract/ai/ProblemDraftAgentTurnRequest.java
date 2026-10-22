package com.aioj.next.contract.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProblemDraftAgentTurnRequest(
        @NotBlank @Size(max = 80) String clientTurnId,
        Long expectedCurrentDraftId,
        @NotBlank @Size(max = 4000) String message,
        @Size(max = 48) String intent
) {
}
