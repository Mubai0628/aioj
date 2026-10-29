package com.aioj.next.ai.controller;

import com.aioj.next.ai.domain.coderun.CodeRunService;
import com.aioj.next.ai.domain.coderun.CodeRunSettingsService;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.ai.AiCodeRunRequest;
import com.aioj.next.contract.ai.AiCodeRunResponse;
import com.aioj.next.contract.ai.AiCodeRunSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Plan 15.5: a signed-in user runs; only an administrator changes the limits. */
class AiCodeRunControllerTest {
    private final CodeRunService runs = mock(CodeRunService.class);
    private final CodeRunSettingsService settings = mock(CodeRunSettingsService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AiCodeRunController(runs, settings)).build();
        SecurityPrincipal principal = new SecurityPrincipal(7L, "student", Set.of(Role.STUDENT));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void onlyAnAdministratorReadsOrChangesTheLimits() throws Exception {
        for (String method : List.of("adminSettings", "updateSettings")) {
            PreAuthorize annotation = java.util.Arrays.stream(AiCodeRunController.class.getMethods())
                    .filter(candidate -> candidate.getName().equals(method))
                    .findFirst().orElseThrow().getAnnotation(PreAuthorize.class);
            assertThat(annotation).as(method).isNotNull();
            assertThat(annotation.value()).isEqualTo("hasRole('ADMIN')");
        }
        for (String method : List.of("run", "limits")) {
            assertThat(java.util.Arrays.stream(AiCodeRunController.class.getMethods())
                    .filter(candidate -> candidate.getName().equals(method))
                    .findFirst().orElseThrow().getAnnotation(PreAuthorize.class)).as(method).isNull();
        }
        assertThat(AiCodeRunController.class.getAnnotation(PreAuthorize.class)).isNull();
    }

    @Test
    void aRunIsMadeAsTheSignedInUser() throws Exception {
        AiCodeRunRequest request = new AiCodeRunRequest(11L, "cpp", "int main(){}", "SAMPLES", null, null, null, null);
        when(runs.run(eq(7L), eq(request))).thenReturn(new AiCodeRunResponse("run-1", "SAMPLES", "AC", 1, 1, null,
                List.of(new AiCodeRunResponse.CaseResult(1, "AC", 3, 1024, "3\n", false, "", null))));

        mockMvc.perform(post("/ai/code-runs")
                        .contentType("application/json")
                        .content("""
                                {"problemId":11,"language":"cpp","sourceCode":"int main(){}","mode":"SAMPLES"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verdict").value("AC"))
                .andExpect(jsonPath("$.data.cases[0].stdout").value("3\n"));

        verify(runs).run(eq(7L), eq(request));
    }

    @Test
    void theRunPanelSeesTheLimitsButNotWhoSetThem() throws Exception {
        when(settings.current()).thenReturn(new AiCodeRunSettings(true, 1, 10, 300, 2_000,
                Instant.parse("2026-10-29T03:00:00Z"), 9L));

        mockMvc.perform(get("/ai/code-runs/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.perMinuteLimit").value(10))
                .andExpect(jsonPath("$.data.updatedBy").doesNotExist());
    }
}
