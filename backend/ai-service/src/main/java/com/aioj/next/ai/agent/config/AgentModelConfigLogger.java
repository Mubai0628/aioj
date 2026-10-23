package com.aioj.next.ai.agent.config;

import com.aioj.next.ai.domain.AiModelConfigService;
import com.aioj.next.ai.domain.AiModelEffectiveConfig;
import com.aioj.next.ai.domain.AiModelScope;
import com.aioj.next.ai.model.api.EndpointTuning;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Startup visibility for the model plane: prints every scope's effective config
 * (provider/model/thinking/source and its own call settings, plan A6.2) so an env
 * takeover or a stale DB row is obvious from the boot log. Secrets are never printed.
 */
@Component
public class AgentModelConfigLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentModelConfigLogger.class);

    private final AiModelConfigService configService;

    public AgentModelConfigLogger(AiModelConfigService configService) {
        this.configService = configService;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (AiModelScope scope : AiModelScope.values()) {
            logEffective(scope);
        }
    }

    private void logEffective(AiModelScope scope) {
        try {
            AiModelEffectiveConfig config = configService.effectiveConfig(scope);
            EndpointTuning tuning = config.tuning();
            log.info("AgentCore model config scope={} source={} provider={} model={} baseUrl={} thinking={} reasoningEffort={} enabled={} hasApiKey={}"
                            + " connectTimeout={} firstByteTimeout={} totalTimeout={} maxConcurrency={} maxRetries={}"
                            + " thinkingFallback={} streaming={}",
                    scope,
                    config.source(),
                    config.provider(),
                    config.model(),
                    config.baseUrl(),
                    config.thinkingEnabled(),
                    config.reasoningEffort(),
                    config.enabled(),
                    config.hasApiKey(),
                    orDefault(tuning.connectTimeout()), orDefault(tuning.firstByteTimeout()),
                    orDefault(tuning.totalTimeout()), orDefault(tuning.maxConcurrency()),
                    orDefault(tuning.maxRetries()), orDefault(tuning.thinkingFallback()),
                    orDefault(tuning.streaming()));
        } catch (RuntimeException ex) {
            log.warn("AgentCore model config scope={} could not be resolved at startup: {}", scope, ex.getMessage());
        }
    }

    private static Object orDefault(Object value) {
        return value == null ? "default" : value;
    }
}
