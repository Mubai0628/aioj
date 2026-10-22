package com.aioj.next.problem.controller;

import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.contract.submission.JudgeMetricsResponse;
import com.aioj.next.problem.domain.JudgeMetricsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/judge")
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class AdminJudgeMetricsController {
    private final JudgeMetricsService judgeMetricsService;

    public AdminJudgeMetricsController(JudgeMetricsService judgeMetricsService) {
        this.judgeMetricsService = judgeMetricsService;
    }

    @GetMapping("/metrics")
    public ApiResponse<JudgeMetricsResponse> metrics() {
        return ApiResponse.ok(judgeMetricsService.metrics());
    }
}
