package com.aioj.next.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCoreEnvironmentBindingTest {
    @Test
    void bindsAgentStepLimitFromProcessEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("AIOJ_AI_AGENTCORE_MAXAGENTSTEPS", "2")));
        AiProperties properties = Binder.get(environment).bind("aioj.ai", Bindable.of(AiProperties.class)).get();
        assertThat(properties.getAgentCore().getMaxAgentSteps()).isEqualTo(2);
    }
}
