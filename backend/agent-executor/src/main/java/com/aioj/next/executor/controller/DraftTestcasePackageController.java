package com.aioj.next.executor.controller;

import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.judge.DraftTestcasePackageRunRequest;
import com.aioj.next.contract.judge.DraftTestcasePackageRunResponse;
import com.aioj.next.executor.config.AgentExecutorProperties;
import com.aioj.next.executor.domain.DraftTestcasePackageRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/v1/internal/sandbox")
public class DraftTestcasePackageController {
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final DraftTestcasePackageRunner runner;
    private final AgentExecutorProperties properties;

    public DraftTestcasePackageController(DraftTestcasePackageRunner runner, AgentExecutorProperties properties) {
        this.runner = runner;
        this.properties = properties;
    }

    @PostMapping(value = "/run-package", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DraftTestcasePackageRunResponse> runPackage(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestPart("metadata") DraftTestcasePackageRunRequest request,
            @RequestPart("package") MultipartFile packageFile
    ) {
        requireInternalToken(token);
        String language = enabledLanguage(request.language());
        requireBoundedSource(request.sourceCode(), "sourceCode");
        String referenceLanguage = null;
        if (request.referenceSourceCode() != null && !request.referenceSourceCode().isBlank()) {
            referenceLanguage = enabledLanguage(request.referenceLanguage());
            requireBoundedSource(request.referenceSourceCode(), "referenceSourceCode");
        }
        DraftTestcasePackageRunRequest normalized = new DraftTestcasePackageRunRequest(
                request.packageSha256(), request.packageSizeBytes(), language, request.sourceCode(),
                referenceLanguage, request.referenceSourceCode(), request.timeLimitMillis(), request.memoryLimitKb(),
                request.maxCases());
        return ApiResponse.ok(runner.run(packageFile, normalized));
    }

    private String enabledLanguage(String language) {
        String normalized = InternalSandboxController.normalizeLanguage(language);
        boolean enabled = properties.getLanguageWhitelist() != null && properties.getLanguageWhitelist().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(InternalSandboxController::normalizeLanguage)
                .anyMatch(normalized::equals);
        if (!enabled) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Sandbox language is not enabled: " + normalized);
        }
        return normalized;
    }

    private void requireBoundedSource(String source, String field) {
        if (source == null || source.isBlank()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, field + " is required");
        }
        if (source.getBytes(StandardCharsets.UTF_8).length > Math.max(1, properties.getMaxSourceBytes())) {
            throw new DomainException(ErrorCode.BAD_REQUEST, field + " exceeds the configured limit");
        }
    }

    private void requireInternalToken(String token) {
        String expected = properties.getInternalApiToken();
        if (expected == null || expected.isBlank() || token == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "internal token missing or invalid");
        }
    }
}
