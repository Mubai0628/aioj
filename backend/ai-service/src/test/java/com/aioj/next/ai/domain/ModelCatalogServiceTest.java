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
                new ModelInfo("deepseek-flash", "deepseek", null, false),
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
        assertThat(response.models().get(0).thinkingEffortModes()).containsExactly("low", "high", "max");
        assertThat(response.models().get(0).deprecated()).isFalse();
        assertThat(response.models().get(1).deprecated()).isTrue();
    }

    /** Plan 15.2 (2026-10-29): every DeepSeek model thinks at low, high or max; retired names are flagged. */
    @Test
    void deepSeekModelsThinkByTheirPrefixAndRetiredNamesAreFlagged() {
        AiModelListResponse.ModelOption flash = ModelCatalogService.modelOption("deepseek",
                new ModelInfo("deepseek-flash", "deepseek", null, false));
        AiModelListResponse.ModelOption pro = ModelCatalogService.modelOption("deepseek",
                new ModelInfo("deepseek-v4-pro", "deepseek", 1048576, false));
        AiModelListResponse.ModelOption oldFlash = ModelCatalogService.modelOption("deepseek",
                new ModelInfo("deepseek-v4-flash", "deepseek", null, false));

        for (AiModelListResponse.ModelOption option : List.of(flash, pro, oldFlash)) {
            assertThat(option.supportsJsonOutput()).isTrue();
            assertThat(option.supportsThinking()).as(option.id()).isTrue();
            assertThat(option.thinkingEffortModes()).as(option.id()).containsExactly("low", "high", "max");
            assertThat(option.recommendedTemperature()).isNull();
        }
        assertThat(flash.deprecated()).isFalse();
        assertThat(pro.deprecated()).isFalse();
        assertThat(pro.contextLength()).isEqualTo(1048576);
        assertThat(oldFlash.deprecated()).as("retired, but still served").isTrue();
    }

    @Test
    void retiredTextProvidersAreAnsweredWithoutACall() {
        for (String provider : List.of("kimi", "moonshot", "openai-compatible", "custom")) {
            AiModelListResponse response = service.listModels(AiModelScope.MEMORY_EXTRACTION, provider,
                    "https://llm.example.test/v1/chat/completions", KEY);

            assertThat(response.fetchStatus()).as(provider).isEqualTo("UNSUPPORTED");
            assertThat(response.errorMessage()).isEqualTo("This provider was retired on 2026-10-29; text scopes use deepseek.");
            assertThat(response.models()).isEmpty();
        }
        verifyNoInteractions(modelClient);
    }

    @Test
    void embeddingsStillListAnOpenAiCompatibleCatalog() {
        when(modelClient.listModels(any(), any())).thenReturn(List.of(new ModelInfo("bge-m3", "local", null, false)));

        AiModelListResponse response = service.listModels(AiModelScope.EMBEDDING, "openai-compatible",
                "https://llm.example.test/v1/embeddings", KEY);

        ArgumentCaptor<ModelEndpointConfig> candidate = ArgumentCaptor.forClass(ModelEndpointConfig.class);
        verify(modelClient).listModels(candidate.capture(), any());
        assertThat(candidate.getValue().provider()).isEqualTo(ProviderId.OPENAI_COMPATIBLE);
        assertThat(response.fetchStatus()).isEqualTo("SUCCESS");
        assertThat(response.models()).extracting(AiModelListResponse.ModelOption::id).containsExactly("bge-m3");
    }

    // Kimi, retired on 2026-10-29:
    // @Test
    // void describesKimiReasoningModelsWithoutEffortModes() {
    //     AiModelListResponse.ModelOption k26 = ModelCatalogService.modelOption("moonshot",
    //             new ModelInfo("kimi-k2.6", "moonshot", 262144, true));
    //     AiModelListResponse.ModelOption v1 = ModelCatalogService.modelOption("moonshot",
    //             new ModelInfo("moonshot-v1-32k", "moonshot", 32768, false));
    //
    //     assertThat(k26.supportsJsonOutput()).isTrue();
    //     assertThat(k26.supportsThinking()).isTrue();
    //     assertThat(k26.thinkingEffortModes()).isEmpty();
    //     assertThat(k26.recommendedTemperature()).isEqualTo(0.6);
    //     assertThat(k26.contextLength()).isEqualTo(262144);
    //     assertThat(v1.supportsThinking()).isFalse();
    // }

    // Kimi, retired on 2026-10-29:
    // @Test
    // void kimiK3IsThinkingOnlyWithEffortLevels() {
    //     AiModelListResponse.ModelOption k3 = ModelCatalogService.modelOption("moonshot",
    //             new ModelInfo("kimi-k3", "moonshot", null, false));
    //
    //     assertThat(k3.supportsThinking()).isTrue();
    //     assertThat(k3.thinkingEffortModes()).containsExactly("high", "max");
    //     assertThat(k3.recommendedTemperature()).isEqualTo(1.0);
    // }

    @Test
    void dashScopeHasNoCatalogAndAMissingKeyIsReportedWithoutACall() {
        AiModelListResponse dashScope = service.listModels(AiModelScope.EMBEDDING, "dashscope",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings", KEY);
        AiModelListResponse keyless = service.listModels(AiModelScope.TEXT_GENERATION, "deepseek",
                "https://api.deepseek.com/chat/completions", null);

        assertThat(dashScope.fetchStatus()).isEqualTo("UNSUPPORTED");
        assertThat(keyless.fetchStatus()).isEqualTo("MISSING_KEY");
        assertThat(keyless.apiKeyConfigured()).isFalse();
        verifyNoInteractions(modelClient);
    }

    @Test
    void aFailingProviderGivesTheSafeMessage() {
        when(modelClient.listModels(any(), any())).thenThrow(new ModelException(ModelErrorKind.AUTHENTICATION,
                ProviderId.DEEPSEEK, null, "AI provider deepseek rejected the API key (HTTP 401)"));

        AiModelListResponse response = service.listModels(AiModelScope.TEXT_GENERATION, "deepseek",
                "https://api.deepseek.com/chat/completions", KEY);

        assertThat(response.fetchStatus()).isEqualTo("FAILED");
        // Plan S7: the category as an error key; the provider's own words stay in the log.
        assertThat(response.errorMessage()).isEqualTo("ai.keyInvalid");
        assertThat(response.models()).isEmpty();
    }
}
