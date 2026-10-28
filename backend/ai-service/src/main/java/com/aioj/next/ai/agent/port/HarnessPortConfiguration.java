package com.aioj.next.ai.agent.port;

import com.aioj.next.ai.agent.context.HarnessContextConfiguration;
import com.aioj.next.ai.config.AiProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** The memory and profile read boundary (plan T1.6), sized like the context contributor pool. */
@Configuration(proxyBeanMethods = false)
public class HarnessPortConfiguration {

    @Bean(destroyMethod = "close")
    public PortCalls portCalls(AiProperties properties) {
        AiProperties.AgentCore.Context settings = properties.getAgentCore().getContext();
        return new PortCalls(settings.getThreads(), settings.getQueueCapacity(),
                Duration.ofMillis(settings.getPortTimeoutMs()), HarnessContextConfiguration.carryLoggingContext());
    }
}
