package com.aioj.next.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aioj.internal")
public class InternalApiProperties extends com.aioj.next.common.security.InternalServiceProperties {

}
