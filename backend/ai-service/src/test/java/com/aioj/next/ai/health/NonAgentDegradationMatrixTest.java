package com.aioj.next.ai.health;

import com.aioj.next.ai.agent.AgentChatFacade;
import com.aioj.next.ai.agent.context.ContextDebugService;
import com.aioj.next.ai.controller.AiController;
import com.aioj.next.ai.controller.AiModelConfigController;
import com.aioj.next.ai.domain.*;
import com.aioj.next.ai.domain.memory.AiMemoryCandidateService;
import com.aioj.next.ai.domain.memory.AiMemoryDebugService;
import com.aioj.next.ai.domain.memory.AiMemoryReviewService;
import com.aioj.next.ai.domain.response.AiAssistantResponseNormalizer;
import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Actual MVC handlers + method security; persistence is mocked, not a real DB integration test. */
class NonAgentDegradationMatrixTest {
    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class MethodSecurity { }

    @ParameterizedTest
    @ValueSource(strings = {"modelProviders", "agentExecutor", "agentChannel", "problemService", "agentHarness"})
    void nonAgentReadAndReviewRoutesRemainAvailableWhenAnAgentSubsystemIsDegraded(String subsystem) {
        var principal = mock(SecurityPrincipal.class);
        when(principal.userId()).thenReturn(42L);
        when(principal.roles()).thenReturn(Set.of(Role.ADMIN));
        var authentication = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            new HealthGroupsConfigurationTest().runner("ai-service", new AtomicReference<>(subsystem))
                    .withUserConfiguration(MethodSecurity.class)
                    .withBean(AgentChatFacade.class, () -> mock(AgentChatFacade.class))
                    .withBean(AiQuotaService.class, () -> mock(AiQuotaService.class))
                    .withBean(AccountImportParseService.class, () -> mock(AccountImportParseService.class))
                    .withBean(AiConversationService.class, () -> mock(AiConversationService.class))
                    .withBean(ContextDebugService.class, () -> mock(ContextDebugService.class))
                    .withBean(AiMemoryService.class, () -> mock(AiMemoryService.class))
                    .withBean(AiLearningProfileService.class, () -> mock(AiLearningProfileService.class))
                    .withBean(AiMemoryCandidateService.class, () -> mock(AiMemoryCandidateService.class))
                    .withBean(AiMemoryDebugService.class, () -> mock(AiMemoryDebugService.class))
                    .withBean(AiMemoryReviewService.class, () -> mock(AiMemoryReviewService.class))
                    .withBean(ProblemDraftStore.class, () -> mock(ProblemDraftStore.class))
                    .withBean(AiAssistantResponseNormalizer.class, () -> mock(AiAssistantResponseNormalizer.class))
                    .withBean(AiModelConfigService.class, () -> mock(AiModelConfigService.class))
                    .withBean(ObjectMapper.class, ObjectMapper::new)
                    .withBean("aiProblemDraftExecutor", Executor.class, () -> Runnable::run)
                    .withBean(AiController.class).withBean(AiModelConfigController.class)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        var health = context.getBean(HealthEndpoint.class);
                        assertThat(health.healthForPath("agent").getStatus().getCode()).isEqualTo("DEGRADED");
                        assertThat(health.healthForPath("readiness").getStatus().getCode()).isEqualTo("UP");
                        var configs = context.getBean(AiModelConfigService.class);
                        var conversations = context.getBean(AiConversationService.class);
                        var drafts = context.getBean(ProblemDraftStore.class);
                        when(configs.listConfigs()).thenReturn(List.of());
                        when(conversations.list(42L, 1L, 20L, null, null, null, null, null, false))
                                .thenReturn(new PageResponse<>(List.of(), 0, 1, 20));
                        var reviewed = mock(ProblemDraftResponse.class);
                        when(reviewed.id()).thenReturn(7L);
                        when(drafts.manualReview(7L, 42L)).thenReturn(reviewed);
                        var mvc = MockMvcBuilders.standaloneSetup(context.getBean(AiController.class),
                                context.getBean(AiModelConfigController.class)).build();
                        mvc.perform(get("/admin/ai-model-configs")).andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(0));
                        mvc.perform(get("/ai/conversations")).andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(0))
                                .andExpect(jsonPath("$.data.total").value(0));
                        mvc.perform(post("/admin/problem-drafts/7/manual-review")).andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(0))
                                .andExpect(jsonPath("$.data.id").value(7));
                        verify(configs).listConfigs();
                        verify(conversations).list(42L, 1L, 20L, null, null, null, null, null, false);
                        verify(drafts).manualReview(7L, 42L);
                        verifyNoInteractions(context.getBean(AgentChatFacade.class));
                    });
        } finally { SecurityContextHolder.clearContext(); }
    }
}
