package com.aioj.next.executor;

import com.aioj.next.executor.config.AgentExecutorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * agent-executor (plan D3, §3.6): runs code for agent tools in the agent
 * sandbox, apart from judging. It scans only its own package and common-lib's
 * error handling, so none of the shared security or JWT setup comes along.
 */
@SpringBootApplication(scanBasePackages = {"com.aioj.next.executor", "com.aioj.next.common.error"})
@EnableConfigurationProperties(AgentExecutorProperties.class)
public class AgentExecutorApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentExecutorApplication.class, args);
    }
}
