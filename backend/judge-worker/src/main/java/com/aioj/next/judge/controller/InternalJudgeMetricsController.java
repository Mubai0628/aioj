package com.aioj.next.judge.controller;

import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.judge.monitor.JudgeRuntimeMetrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/internal/judge")
public class InternalJudgeMetricsController {
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final JudgeRuntimeMetrics metrics;

    public InternalJudgeMetricsController(JudgeRuntimeMetrics metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/metrics")
    public ApiResponse<JudgeRuntimeMetrics.Snapshot> metrics() {
        return ApiResponse.ok(metrics.snapshot());
    }
}
