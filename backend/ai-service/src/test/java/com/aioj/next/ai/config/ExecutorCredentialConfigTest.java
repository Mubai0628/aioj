package com.aioj.next.ai.config;

import com.aioj.next.common.security.InternalServiceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ExecutorCredentialConfigTest {
    @Test
    void existingAiExecutorTokenNameFeedsCanonicalConfigAndExplicitCanonicalSettingWins() throws Exception {
        var environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("ai", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        environment.getPropertySources().addFirst(new MapPropertySource("existing-name",
                Map.of("aioj.ai.agent-executor-token", "test-executor-credential")));
        assertThat(bind(environment).getOutbound().get("agent-executor").getToken())
                .isEqualTo("test-executor-credential");
        environment.getPropertySources().addFirst(new MapPropertySource("canonical",
                Map.of("aioj.internal.outbound.agent-executor.token", "explicit-test-credential")));
        assertThat(bind(environment).getOutbound().get("agent-executor").getToken())
                .isEqualTo("explicit-test-credential");
    }

    private InternalServiceProperties bind(StandardEnvironment environment) {
        return Binder.get(environment).bind("aioj.internal", Bindable.of(InternalServiceProperties.class)).get();
    }
}
