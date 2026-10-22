package com.aioj.next.ai.domain.draftagent;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.config.InternalApiProperties;
import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.judge.DraftTestcasePackageRunRequest;
import com.aioj.next.contract.judge.DraftTestcasePackageRunResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;

@Component
public class DraftTestcasePackageAcceptanceClient {
    private static final ParameterizedTypeReference<ApiResponse<DraftTestcasePackageRunResponse>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private final RestClient restClient;

    public DraftTestcasePackageAcceptanceClient(AiProperties properties, InternalApiProperties internalApiProperties) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        requestFactory.setReadTimeout(Duration.ofMinutes(10));
        this.restClient = RestClient.builder()
                .baseUrl(stripTrailingSlash(properties.getJudgeWorkerUri()))
                .defaultHeader("X-Internal-Token", internalApiProperties.getApiToken())
                .requestFactory(requestFactory)
                .build();
    }

    public DraftTestcasePackageRunResponse run(Path packagePath, DraftTestcasePackageRunRequest request) {
        if (packagePath == null || request == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Draft testcase package acceptance request is incomplete");
        }
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("metadata", request);
        parts.add("package", new FileSystemResource(packagePath));
        ApiResponse<DraftTestcasePackageRunResponse> response = restClient.post()
                .uri("/api/v1/internal/sandbox/run-package")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(RESPONSE_TYPE);
        if (response == null || response.code() != 0 || response.data() == null) {
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE,
                    response == null ? "Draft testcase package acceptance returned no response" : response.message());
        }
        return response.data();
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8203";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
