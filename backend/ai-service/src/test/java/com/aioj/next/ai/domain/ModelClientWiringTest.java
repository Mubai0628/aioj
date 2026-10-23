package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.model.ModelClientConfiguration;
import com.aioj.next.ai.model.api.ModelClient;
import com.aioj.next.ai.persistence.mapper.AiModelConfigMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The config service tests connectivity and lists models through the model client,
 * whose configuration source reads the config service: the loop must not stop the
 * application from starting (it did on the first T1.3 stack run).
 */
class ModelClientWiringTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withAllowCircularReferences(false)
            .withUserConfiguration(ModelClientConfiguration.class)
            .withBean(AiProperties.class, AiProperties::new)
            .withBean(AiModelConfigMapper.class, () -> mock(AiModelConfigMapper.class))
            .withBean(AiQuotaService.class, () -> mock(AiQuotaService.class))
            .withBean(AiModelConfigEndpointSource.class)
            .withBean(ModelCompletions.class)
            .withBean(ModelCatalogService.class)
            .withBean(AiModelConfigService.class);

    @Test
    void theConfigServiceAndTheModelClientStartTogether() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ModelClient.class);
            assertThat(context).hasSingleBean(AiModelConfigService.class);
        });
    }
}
