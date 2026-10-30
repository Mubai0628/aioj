package com.aioj.next.gateway;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The client address and the per-address limits (T2.2). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProtectionProperties.class)
class GatewayProtectionConfig {
    @Bean
    ClientIpWebFilter clientIpWebFilter(GatewayProtectionProperties properties) {
        return new ClientIpWebFilter(new ClientAddress(properties.getClientIp().getTrustedProxies(),
                properties.getClientIp().getTrustedHops()));
    }

    @Bean
    RateLimitWebFilter rateLimitWebFilter(GatewayProtectionProperties properties, ApplicationContext context,
                                          ObjectProvider<MeterRegistry> meters) {
        var policies = RateLimitWebFilter.policies(properties.getRateLimit());
        return new RateLimitWebFilter(policies, RateLimitWebFilter.redis(context, policies),
                properties.getRateLimit(), meters.getIfAvailable());
    }
}
