package com.aioj.next.judge.controller;

import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.judge.config.JudgeWorkerProperties;
import com.aioj.next.judge.monitor.JudgeRuntimeMetrics;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/v1/internal/judge")
public class InternalJudgeMetricsController {
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final JudgeRuntimeMetrics metrics;
    private final JudgeWorkerProperties properties;

    public InternalJudgeMetricsController(JudgeRuntimeMetrics metrics, JudgeWorkerProperties properties) {
        this.metrics = metrics;
        this.properties = properties;
    }

    @GetMapping("/metrics")
    public ApiResponse<JudgeRuntimeMetrics.Snapshot> metrics(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token) {
        String expected = properties.getInternalApiToken();
        if (expected == null || expected.isBlank() || token == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "internal token missing or invalid");
        }
        return ApiResponse.ok(metrics.snapshot());
    }
}
