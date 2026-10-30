package com.aioj.next.executor.config;

import com.aioj.next.common.security.InternalApiTokenFilter;
import com.aioj.next.common.web.TraceIdFilter;
import com.aioj.next.sandbox.client.DefaultOutputComparator;
import com.aioj.next.sandbox.client.OutputComparator;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
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

    /** First of all, so every log line of a run, a rejected caller's included, carries ai-service's trace id. */
    @Bean
    FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.setOrder(TraceIdFilter.ORDER);
        return registration;
    }

    /** Checks agent-executor's own inbound token, which only ai-service holds, before any other check. */
    @Bean
    FilterRegistrationBean<InternalApiTokenFilter> internalApiTokenFilter(InternalApiProperties properties) {
        FilterRegistrationBean<InternalApiTokenFilter> registration =
                new FilterRegistrationBean<>(new InternalApiTokenFilter(properties.getInbound(), com.aioj.next.common.security.InternalApiAccess.agentExecutor()));
        registration.addUrlPatterns(INTERNAL_PATHS);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /** After the token check, so a caller without the token never takes the only permit. */
    @Bean
    FilterRegistrationBean<ExecutionConcurrencyFilter> executionConcurrencyFilter(AgentExecutorProperties properties,
                                                                                  ObjectProvider<MeterRegistry> meters) {
        FilterRegistrationBean<ExecutionConcurrencyFilter> registration = new FilterRegistrationBean<>(
                new ExecutionConcurrencyFilter(properties.getMaxConcurrentExecutions(), meters.getIfAvailable()));
        registration.addUrlPatterns(INTERNAL_PATHS);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
