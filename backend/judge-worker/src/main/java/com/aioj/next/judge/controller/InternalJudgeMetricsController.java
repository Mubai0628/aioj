package com.aioj.next.judge.controller;

import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.common.security.InternalApiTokenFilter;
import com.aioj.next.judge.config.InternalApiProperties;
import com.aioj.next.judge.monitor.JudgeRuntimeMetrics;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/internal/judge")
public class InternalJudgeMetricsController {
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final JudgeRuntimeMetrics metrics;
    private final InternalApiProperties internalServices;

    public InternalJudgeMetricsController(JudgeRuntimeMetrics metrics, InternalApiProperties internalServices) {
        this.metrics = metrics;
        this.internalServices = internalServices;
    }

    /** The filter authorizes the caller first; judge-worker has no Spring Security, so check again here. */
    @GetMapping("/metrics")
    public ApiResponse<JudgeRuntimeMetrics.Snapshot> metrics(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token) {
        var credential = internalServices.getInbound().get("problem-service");
        if (credential == null || !InternalApiTokenFilter.matches(token, credential.getToken(), credential.getNextToken())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "internal token missing or invalid");
        }
        return ApiResponse.ok(metrics.snapshot());
    }
}
