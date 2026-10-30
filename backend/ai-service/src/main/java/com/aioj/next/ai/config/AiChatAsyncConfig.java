package com.aioj.next.ai.config;

import com.aioj.next.ai.agent.context.HarnessContextConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AiChatAsyncConfig {
    @Bean(name = "aiChatTurnExecutor")
    public Executor aiChatTurnExecutor(AiProperties properties) {
        AiProperties.Capacity capacity = properties.getCapacity();
        int corePoolSize = Math.max(1, capacity.getChatCorePoolSize());
        int maxPoolSize = Math.max(corePoolSize, capacity.getChatMaxPoolSize());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(Math.max(0, capacity.getChatQueueCapacity()));
        executor.setThreadNamePrefix("ai-chat-turn-");
        // The turn logs, and publishes its channel event, under the request's trace id (T1.7).
        executor.setTaskDecorator(HarnessContextConfiguration.carryLoggingContext()::apply);
        executor.initialize();
        return executor;
    }

    @Bean(name = "aiProblemDraftExecutor")
    public Executor aiProblemDraftExecutor(AiProperties properties) {
        AiProperties.Capacity capacity = properties.getCapacity();
        int poolSize = Math.max(1, capacity.getProblemDraftConcurrency());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(poolSize * 2);
        executor.setThreadNamePrefix("ai-problem-draft-");
        executor.setTaskDecorator(HarnessContextConfiguration.carryLoggingContext()::apply);
        executor.initialize();
        return executor;
    }
}
