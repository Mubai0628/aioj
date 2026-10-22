package com.aioj.next.ai.controller;

import com.aioj.next.ai.domain.AiLearningProfileService;
import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.contract.ai.AiRecommendationProfileSignal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/internal/ai")
public class InternalAiProfileController {

    private final AiLearningProfileService profileService;

    public InternalAiProfileController(AiLearningProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/users/{userId}/recommendation-signals")
    @PreAuthorize("hasRole('INTERNAL')")
    public ApiResponse<List<AiRecommendationProfileSignal>> recommendationSignals(
            @PathVariable Long userId,
            @RequestParam(required = false) Integer limit
    ) {
        return ApiResponse.ok(profileService.recommendationSignals(userId, limit));
    }
}