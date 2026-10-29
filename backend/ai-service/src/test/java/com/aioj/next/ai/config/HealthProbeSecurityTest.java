package com.aioj.next.ai.config;

import com.aioj.next.common.security.JwtTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = HealthProbeSecurityTest.ProbeController.class)
@Import({SecurityConfig.class, HealthProbeSecurityTest.Properties.class, HealthProbeSecurityTest.ProbeController.class})
@ContextConfiguration(classes = {SecurityConfig.class, HealthProbeSecurityTest.Properties.class, HealthProbeSecurityTest.ProbeController.class})
class HealthProbeSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtTokenService tokens;

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class Properties {
        @Bean InternalApiProperties internalApiProperties() { return new InternalApiProperties(); }
    }
    @RestController
    static class ProbeController {
        @GetMapping({"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness",
                "/actuator/health/agent", "/actuator/metrics", "/private"})
        Map<String, String> status() { return Map.of("status", "UP"); }
    }
    @Test
    void healthGroupsNeedNoBearerOrInternalCredential() throws Exception {
        for (String path : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/health/agent")) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }
    }
    @Test
    void otherActuatorAndOrdinaryPathsRemainAuthenticated() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/private")).andExpect(status().isUnauthorized());
    }
}
