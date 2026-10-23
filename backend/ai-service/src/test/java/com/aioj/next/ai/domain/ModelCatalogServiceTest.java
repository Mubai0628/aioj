package com.aioj.next.ai.domain;

import com.aioj.next.ai.model.api.ModelClient;
import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ModelErrorKind;
import com.aioj.next.ai.model.api.ModelException;
import com.aioj.next.ai.model.api.ModelInfo;
import com.aioj.next.ai.model.api.ProviderId;
import com.aioj.next.contract.ai.AiModelListResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModelCatalogServiceTest {
    private static final EnvironmentAiModelConfigResolver.ApiKeyResolution KEY =
            new EnvironmentAiModelConfigResolver.ApiKeyResolution("sk-catalog", "sk-***", "environment",
                    "DEEPSEEK_API_KEY");

    private final ModelClient modelClient = mock(ModelClient.class);
    private final ModelCatalogService service = new ModelCatalogService(modelClient);

    @Test
    void asksTheConfiguredProviderAndDescribesDeepSeekModels() {
        when(modelClient.listModels(any(), any())).thenReturn(List.of(
                new ModelInfo("deepseek-v4-flash", "deepseek", null, false),
                new ModelInfo("deepseek-chat", "deepseek", null, false)));

        AiModelListResponse response = service.listModels(AiModelScope.TEXT_GENERATION, "deepseek",
                "https://api.deepseek.com/chat/completions", KEY);

        ArgumentCaptor<ModelEndpointConfig> candidate = ArgumentCaptor.forClass(ModelEndpointConfig.class);
        verify(modelClient).listModels(candidate.capture(), any());
        assertThat(candidate.getValue().provider()).isEqualTo(ProviderId.DEEPSEEK);
        assertThat(candidate.getValue().baseUrl()).isEqualTo("https://api.deepseek.com/chat/completions");
        assertThat(response.fetchStatus()).isEqualTo("SUCCESS");
        assertThat(response.apiKeyEnvName()).isEqualTo("DEEPSEEK_API_KEY");
        assertThat(response.models()).hasSize(2);
        assertThat(response.models().get(0).supportsJsonOutput()).isTrue();
        assertThat(response.models().get(0).supportsThinking()).isTrue();
        assertThat(response.models().get(0).thinkingEffortModes()).containsExactly("high", "max");
        assertThat(response.models().get(1).deprecated()).isTrue();
    }

    @Test
    void describesKimiReasoningModelsWithoutEffortModes() {
        AiModelListResponse.ModelOption k26 = ModelCatalogService.modelOption("moonshot",
                new ModelInfo("kimi-k2.6", "moonshot", 262144, true));
        AiModelListResponse.ModelOption v1 = ModelCatalogService.modelOption("moonshot",
                new ModelInfo("moonshot-v1-32k", "moonshot", 32768, false));

        assertThat(k26.supportsJsonOutput()).isTrue();
        assertThat(k26.supportsThinking()).isTrue();
        assertThat(k26.thinkingEffortModes()).isEmpty();
        assertThat(k26.recommendedTemperature()).isEqualTo(0.6);
        assertThat(k26.contextLength()).isEqualTo(262144);
        assertThat(v1.supportsThinking()).isFalse();
    }

    @Test
    void kimiK3IsThinkingOnlyWithEffortLevels() {
        AiModelListResponse.ModelOption k3 = ModelCatalogService.modelOption("moonshot",
                new ModelInfo("kimi-k3", "moonshot", null, false));

        assertThat(k3.supportsThinking()).isTrue();
        assertThat(k3.thinkingEffortModes()).containsExactly("high", "max");
        assertThat(k3.recommendedTemperature()).isEqualTo(1.0);
    }

    @Test
    void dashScopeHasNoCatalogAndAMissingKeyIsReportedWithoutACall() {
        AiModelListResponse dashScope = service.listModels(AiModelScope.EMBEDDING, "dashscope",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings", KEY);
        AiModelListResponse keyless = service.listModels(AiModelScope.TEXT_GENERATION, "kimi",
                "https://api.moonshot.cn/v1/chat/completions", null);

        assertThat(dashScope.fetchStatus()).isEqualTo("UNSUPPORTED");
        assertThat(keyless.fetchStatus()).isEqualTo("MISSING_KEY");
        assertThat(keyless.apiKeyConfigured()).isFalse();
        verifyNoInteractions(modelClient);
    }

    @Test
    void aFailingProviderGivesTheSafeMessage() {
        when(modelClient.listModels(any(), any())).thenThrow(new ModelException(ModelErrorKind.AUTHENTICATION,
                ProviderId.KIMI, null, "AI provider kimi rejected the API key (HTTP 401)"));

        AiModelListResponse response = service.listModels(AiModelScope.TEXT_GENERATION, "kimi",
                "https://api.moonshot.cn/v1/chat/completions", KEY);

        assertThat(response.fetchStatus()).isEqualTo("FAILED");
        assertThat(response.errorMessage()).isEqualTo("AI provider kimi rejected the API key (HTTP 401)");
        assertThat(response.models()).isEmpty();
    }
}
