package com.aioj.next.ai.controller;

import com.aioj.next.ai.domain.embedding.AiEmbeddingService;
import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.contract.ai.AiEmbeddingRequest;
import com.aioj.next.contract.ai.AiEmbeddingResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/ai")
public class InternalAiEmbeddingController {
    private final AiEmbeddingService embeddingService;

    public InternalAiEmbeddingController(AiEmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @PostMapping("/embeddings")
    @PreAuthorize("hasRole('INTERNAL')")
    public ApiResponse<AiEmbeddingResponse> embed(@Valid @RequestBody AiEmbeddingRequest request) {
        return ApiResponse.ok(embeddingService.embed(request));
    }
}
