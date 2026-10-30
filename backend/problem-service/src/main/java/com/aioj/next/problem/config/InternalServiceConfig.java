package com.aioj.next.problem.config;

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
}
