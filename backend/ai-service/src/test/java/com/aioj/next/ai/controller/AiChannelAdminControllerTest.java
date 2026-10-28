package com.aioj.next.ai.controller;

import com.aioj.next.ai.agent.channel.ChannelReplayService;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.ai.AiChannelReplayRequest;
import com.aioj.next.contract.ai.AiChannelReplayResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiChannelAdminControllerTest {
    private final ChannelReplayService replayService = mock(ChannelReplayService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AiChannelAdminController(replayService)).build();
        SecurityPrincipal principal = new SecurityPrincipal(99L, "admin", Set.of(Role.ADMIN));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void controllerIsAdminOnly() {
        PreAuthorize annotation = AiChannelAdminController.class.getAnnotation(PreAuthorize.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("hasRole('ADMIN')");
    }

    @Test
    void replayRunsAsTheSignedInAdmin() throws Exception {
        AiChannelReplayRequest request = new AiChannelReplayRequest("AI_JUDGED_SUBMISSION_ANALYSIS", 41L, null);
        when(replayService.replay(eq(99L), eq(request)))
                .thenReturn(new AiChannelReplayResponse("AI_JUDGED_SUBMISSION_ANALYSIS", 41L, 1));

        mockMvc.perform(post("/ai/admin/channel/replay")
                        .contentType("application/json")
                        .content("""
                                {"consumer":"AI_JUDGED_SUBMISSION_ANALYSIS","eventId":41}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consumer").value("AI_JUDGED_SUBMISSION_ANALYSIS"))
                .andExpect(jsonPath("$.data.requeued").value(1));

        verify(replayService).replay(eq(99L), eq(request));
    }
}
