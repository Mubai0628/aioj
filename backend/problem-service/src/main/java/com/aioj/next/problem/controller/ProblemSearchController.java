package com.aioj.next.problem.controller;

import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.contract.problem.Difficulty;
import com.aioj.next.contract.problem.ProblemSearchResponse;
import com.aioj.next.problem.domain.search.ProblemSearchService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/problems")
public class ProblemSearchController {
    private final ProblemSearchService searchService;

    public ProblemSearchController(ProblemSearchService searchService) {
        this.searchService = searchService;
    }

    /** Semantic search spends embedding calls and is limited per user, so it needs a signed-in user. */
    @GetMapping("/search")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ProblemSearchResponse> search(@RequestParam("q") String query,
                                                     @RequestParam(defaultValue = "keyword") String mode,
                                                     @RequestParam(required = false) Difficulty difficulty,
                                                     @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(searchService.search(query, mode, difficulty, limit));
    }
}
