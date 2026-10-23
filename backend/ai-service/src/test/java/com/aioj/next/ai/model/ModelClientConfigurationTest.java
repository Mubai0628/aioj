package com.aioj.next.ai.model;

import com.aioj.next.ai.model.api.ModelCallListener;
import com.aioj.next.ai.model.api.ModelClient;
import com.aioj.next.ai.model.api.ModelConfigSource;
import com.aioj.next.ai.model.config.ModelClientProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ModelClientConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ModelClientConfiguration.class);

    @Test
    void wiresOneClientAndBindsItsSettings() {
        runner.withBean(ModelConfigSource.class, () -> scope -> null)
                .withBean(ModelCallListener.class, () -> record -> {
                })
                .withPropertyValues(
                        "aioj.ai.model-client.total-timeout=90s",
                        "aioj.ai.model-client.retry.max-retries=1",
                        "aioj.ai.model-client.bulkhead.scopes.TEXT_GENERATION=3")
                .run(context -> {
                    assertThat(context).hasSingleBean(ModelClient.class);
                    ModelClientProperties properties = context.getBean(ModelClientProperties.class);
                    assertThat(properties.getTotalTimeout()).isEqualTo(Duration.ofSeconds(90));
                    assertThat(properties.getRetry().getMaxRetries()).isEqualTo(1);
                    assertThat(properties.getBulkhead().concurrencyFor("TEXT_GENERATION")).isEqualTo(3);
                    assertThat(properties.getBulkhead().concurrencyFor("PROBLEM_DRAFT"))
                            .as("defaults follow aioj.ai.capacity").isEqualTo(2);
                    assertThat(properties.getBulkhead().concurrencyFor("SOMETHING_NEW")).isEqualTo(2);
                });
    }

    @Test
    void theClientNeedsAConfigurationSource() {
        runner.run(context -> assertThat(context).hasFailed());
    }
}
