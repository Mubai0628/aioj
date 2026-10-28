package com.aioj.next.ai.agent.context;

import com.aioj.next.agent.harness.context.api.ContextAssembler;
import com.aioj.next.agent.harness.context.api.ContextAssemblers;
import com.aioj.next.ai.config.AiProperties;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.function.UnaryOperator;

/** The Harness context assembler for the student chat (plan T1.6), on its own bounded pool. */
@Configuration(proxyBeanMethods = false)
public class HarnessContextConfiguration {

    @Bean(destroyMethod = "close")
    public ContextAssembler harnessContextAssembler(AiProperties properties) {
        AiProperties.AgentCore.Context settings = properties.getAgentCore().getContext();
        return ContextAssemblers.create(settings.getThreads(), settings.getQueueCapacity(), carryLoggingContext());
    }

    /** Contributor threads log with the turn's MDC (trace id and friends), as the request thread did. */
    static UnaryOperator<Runnable> carryLoggingContext() {
        return task -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (context == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(context);
                }
                try {
                    task.run();
                } finally {
                    if (previous == null) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(previous);
                    }
                }
            };
        };
    }
}
