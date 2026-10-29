package com.aioj.next.judge.config;

import com.aioj.next.common.security.InternalServiceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aioj.internal")
public class InternalApiProperties extends InternalServiceProperties { }
