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
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registries) {
        return new InternalRestClientFactory(properties, target -> registries.ifAvailable(
                registry -> registry.counter("aioj.internal.http.transaction", "target", target).increment()));
    }
}
