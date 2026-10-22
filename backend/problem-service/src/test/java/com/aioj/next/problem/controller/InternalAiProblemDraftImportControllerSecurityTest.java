package com.aioj.next.problem.controller;

import com.aioj.next.common.security.JwtProperties;
import com.aioj.next.contract.problem.AiProblemDraftImportPrepareResponse;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.aioj.next.problem.config.InternalApiProperties;
import com.aioj.next.problem.config.SecurityConfig;
import com.aioj.next.problem.domain.AiProblemDraftImportService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InternalAiProblemDraftImportController.class)
@Import({SecurityConfig.class, InternalAiProblemDraftImportController.class})
@TestPropertySource(properties = {
        "aioj.internal.api-token=problem-import-test-token",
        "aioj.security.jwt.hmac-secret=problem-import-security-test-secret-0123456789"
})
class InternalAiProblemDraftImportControllerSecurityTest {
    private static final String PREPARE_BODY = """
            {
              "workspaceId": "workspace-1",
              "draftId": 31,
              "actorUserId": 88,
              "reviewRisk": "STANDARD",
              "testcaseVerificationMode": "AGENT_VERIFIED",
              "problem": {
                "title": "AI problem",
                "difficulty": "EASY",
                "statement": "statement",
                "tags": ["array"],
                "testCases": [{"input":"1\\n","expectedOutput":"1\\n","sample":true}],
                "timeLimitMillis": 1000,
                "memoryLimitKb": 262144
              }
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiProblemDraftImportService importService;

    @Test
    void prepareRejectsMissingInternalToken() throws Exception {
        mockMvc.perform(post("/api/v1/internal/ai-problem-drafts/import/prepare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PREPARE_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void prepareAcceptsConfiguredInternalToken() throws Exception {
        when(importService.prepare(any())).thenReturn(new AiProblemDraftImportPrepareResponse(
                101L, true, ProblemVisibility.PRIVATE, "workspace-1", 31L));

        mockMvc.perform(post("/api/v1/internal/ai-problem-drafts/import/prepare")
                        .header("X-Internal-Token", "problem-import-test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PREPARE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.problemId").value(101))
                .andExpect(jsonPath("$.data.created").value(true));
    }

    @SpringBootConfiguration
    @EnableConfigurationProperties({JwtProperties.class, InternalApiProperties.class})
    static class TestApplication {
    }
}
