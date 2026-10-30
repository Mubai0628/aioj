package com.aioj.next.problem.config;

import com.aioj.next.common.api.MdcContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableConfigurationProperties(PlagiarismProperties.class)
public class PlagiarismConfig {
    @Bean
    @Qualifier("plagiarismExecutor")
    Executor plagiarismExecutor(PlagiarismProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("plagiarism-");
        executor.setCorePoolSize(Math.max(1, properties.getExecutorPoolSize()));
        executor.setMaxPoolSize(Math.max(1, properties.getExecutorPoolSize()));
        executor.setQueueCapacity(16);
        // The plagiarism call to ai-service carries the request's trace id.
        executor.setTaskDecorator(MdcContext::wrap);
        executor.initialize();
        return executor;
    }
}
