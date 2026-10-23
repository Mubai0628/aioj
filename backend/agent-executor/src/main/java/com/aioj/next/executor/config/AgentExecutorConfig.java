package com.aioj.next.executor.config;

import com.aioj.next.common.security.InternalApiTokenFilter;
import com.aioj.next.sandbox.client.DefaultOutputComparator;
import com.aioj.next.sandbox.client.OutputComparator;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class AgentExecutorConfig {
    private static final String INTERNAL_PATHS = "/api/v1/internal/*";

    @Bean
    OutputComparator outputComparator() {
        return new DefaultOutputComparator();
    }

    /** Checks agent-executor's own inbound token, which only ai-service holds, before anything else. */
    @Bean
    FilterRegistrationBean<InternalApiTokenFilter> internalApiTokenFilter(AgentExecutorProperties properties) {
        FilterRegistrationBean<InternalApiTokenFilter> registration =
                new FilterRegistrationBean<>(new InternalApiTokenFilter(properties.getInternalApiToken()));
        registration.addUrlPatterns(INTERNAL_PATHS);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /** After the token check, so a caller without the token never takes the only permit. */
    @Bean
    FilterRegistrationBean<ExecutionConcurrencyFilter> executionConcurrencyFilter(AgentExecutorProperties properties) {
        FilterRegistrationBean<ExecutionConcurrencyFilter> registration =
                new FilterRegistrationBean<>(new ExecutionConcurrencyFilter(properties.getMaxConcurrentExecutions()));
        registration.addUrlPatterns(INTERNAL_PATHS);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
