package com.aioj.next.judge.config;

import com.aioj.next.common.http.InternalRestClientFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(InternalApiProperties.class)
public class InternalServiceConfig {
    @Bean
    InternalRestClientFactory internalRestClientFactory(InternalApiProperties properties,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registries,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.observation.ObservationRegistry> observations) {
        return new InternalRestClientFactory(properties, registries.getIfAvailable(), observations.getIfAvailable());
    }

    /** Ahead of the token check, so a rejected caller is logged under its trace id too. */
    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<com.aioj.next.common.web.TraceIdFilter> traceIdFilter() {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
                new com.aioj.next.common.web.TraceIdFilter());
        registration.setOrder(com.aioj.next.common.web.TraceIdFilter.ORDER);
        return registration;
    }

    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<com.aioj.next.common.security.InternalApiTokenFilter> internalApiTokenFilter(InternalApiProperties properties) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
                new com.aioj.next.common.security.InternalApiTokenFilter(properties.getInbound(),
                        com.aioj.next.common.security.InternalApiAccess.judgeWorker()));
        registration.addUrlPatterns("/api/v1/internal/*");
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
