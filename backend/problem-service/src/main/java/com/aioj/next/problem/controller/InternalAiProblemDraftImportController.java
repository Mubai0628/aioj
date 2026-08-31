package com.aioj.next.problem.controller;

import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.contract.problem.AiProblemDraftImportCommitRequest;
import com.aioj.next.contract.problem.AiProblemDraftImportCommitResponse;
import com.aioj.next.contract.problem.AiProblemDraftImportPrepareRequest;
import com.aioj.next.contract.problem.AiProblemDraftImportPrepareResponse;
import com.aioj.next.problem.domain.AiProblemDraftImportService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/ai-problem-drafts/import")
@PreAuthorize("hasRole('INTERNAL')")
public class InternalAiProblemDraftImportController {
    private final AiProblemDraftImportService importService;

    public InternalAiProblemDraftImportController(AiProblemDraftImportService importService) {
        this.importService = importService;
    }

    @PostMapping("/prepare")
    public ApiResponse<AiProblemDraftImportPrepareResponse> prepare(
            @RequestBody @Valid AiProblemDraftImportPrepareRequest request) {
        return ApiResponse.ok(importService.prepare(request));
    }

    @PostMapping("/{problemId}/commit")
    public ApiResponse<AiProblemDraftImportCommitResponse> commit(
            @PathVariable Long problemId,
            @RequestBody @Valid AiProblemDraftImportCommitRequest request) {
        return ApiResponse.ok(importService.commit(problemId, request));
    }
}
