package com.aioj.next.problem.controller;

import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.contract.problem.ProblemRecommendationsResponse;
import com.aioj.next.problem.domain.recommendation.ProblemRecommendationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/problems")
public class ProblemRecommendationController {
    private final ProblemRecommendationService recommendationService;

    public ProblemRecommendationController(ProblemRecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    /** The catalog under /problems is public; recommendations are personal, so they need a signed-in user. */
    @GetMapping("/recommendations")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ProblemRecommendationsResponse> recommendations(@RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(recommendationService.recommend(limit));
    }
}
