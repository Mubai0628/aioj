package com.aioj.next.ai.model;

import com.aioj.next.ai.model.api.ModelCallListener;
import com.aioj.next.ai.model.api.ModelClient;
import com.aioj.next.ai.model.api.ModelConfigSource;
import com.aioj.next.ai.model.config.ModelClientProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the model call package; the configuration source and listeners come from ai-service. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModelClientProperties.class)
public class ModelClientConfiguration {

    @Bean
    public ModelClient modelClient(ModelConfigSource configSource, ObjectProvider<ModelCallListener> listeners,
                                   ModelClientProperties properties) {
        return new DefaultModelClient(configSource, listeners.orderedStream().toList(), properties);
    }
}
