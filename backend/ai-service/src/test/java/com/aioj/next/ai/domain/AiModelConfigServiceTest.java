package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.entity.AiModelConfigEntity;
import com.aioj.next.ai.persistence.mapper.AiModelConfigMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiModelListResponse;
import com.aioj.next.contract.ai.AiModelConfigResponse;
import com.aioj.next.contract.ai.AiModelConfigTestRequest;
import com.aioj.next.contract.ai.AiModelConfigUpdateRequest;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aioj.next.ai.model.DefaultModelClient;
import com.aioj.next.ai.model.api.CallOptions;
import com.aioj.next.ai.model.api.CallProfile;
import com.aioj.next.ai.model.api.ChatMessage;
import com.aioj.next.ai.model.api.EndpointTuning;
import com.aioj.next.ai.model.api.ModelCallRecord;
import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ModelErrorKind;
import com.aioj.next.ai.model.api.ModelException;
import com.aioj.next.ai.model.api.ModelRequest;
import com.aioj.next.ai.model.api.ProviderId;
import com.aioj.next.ai.model.config.ModelClientProperties;
import com.aioj.next.contract.ai.AiModelCallSettings;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiModelConfigServiceTest {

    @Test
    void environmentFallbackUsesDeepSeekTextButDoesNotUseTextKeyForEmbedding() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "deepseek-text-key"));
        fixture.properties.getEmbedding().setApiKey("");

        AiModelEffectiveConfig text = fixture.service.effectiveConfig(AiModelScope.TEXT_GENERATION);
        AiModelEffectiveConfig memoryExtraction = fixture.service.effectiveConfig(AiModelScope.MEMORY_EXTRACTION);
        AiModelEffectiveConfig reportAnalysis = fixture.service.effectiveConfig(AiModelScope.REPORT_ANALYSIS);
        AiModelEffectiveConfig draft = fixture.service.effectiveConfig(AiModelScope.PROBLEM_DRAFT);
        AiModelEffectiveConfig embedding = fixture.service.effectiveConfig(AiModelScope.EMBEDDING);

        assertThat(text.provider()).isEqualTo("deepseek");
        assertThat(text.model()).isEqualTo("deepseek-v4-pro");
        assertThat(text.hasApiKey()).isTrue();
        assertThat(text.apiKeyEnvName()).isEqualTo("DEEPSEEK_API_KEY");
        assertThat(memoryExtraction.inherited()).isTrue();
        assertThat(memoryExtraction.apiKey()).isEqualTo("deepseek-text-key");
        assertThat(reportAnalysis.inherited()).isTrue();
        assertThat(reportAnalysis.apiKey()).isEqualTo("deepseek-text-key");
        assertThat(draft.inherited()).isTrue();
        assertThat(draft.apiKey()).isEqualTo("deepseek-text-key");
        assertThat(embedding.hasApiKey()).isFalse();
        assertThat(embedding.apiKeySource()).isEqualTo("NONE");
    }

    @Test
    void aiApiKeyDoesNotShadowDeepSeekProviderKey() {
        Fixture fixture = new Fixture(Map.of(
                "AI_API_KEY", "wrong-global-key",
                "DEEPSEEK_API_KEY", "deepseek-key"
        ));

        AiModelEffectiveConfig text = fixture.service.effectiveConfig(AiModelScope.TEXT_GENERATION);

        assertThat(text.apiKey()).isEqualTo("deepseek-key");
        assertThat(text.apiKeyEnvName()).isEqualTo("DEEPSEEK_API_KEY");
        assertThat(text.apiKey()).isNotEqualTo("wrong-global-key");
    }

    // Kimi, retired on 2026-10-29 (its key is no longer read):
    // @Test
    // void kimiUsesKimiKeyEvenWhenAiApiKeyExists() {
    //     Fixture fixture = new Fixture(Map.of(
    //             "AI_API_KEY", "wrong-global-key",
    //             "DEEPSEEK_API_KEY", "deepseek-key",
    //             "KIMI_API_KEY", "kimi-key"
    //     ));
    //     AiModelConfigEntity text = new AiModelConfigEntity();
    //     text.setId(1L);
    //     text.setScope("TEXT_GENERATION");
    //     text.setEnabled(true);
    //     text.setProvider("moonshot");
    //     text.setBaseUrl("https://api.moonshot.ai/v1/chat/completions");
    //     text.setModel("kimi-k2.6");
    //     text.setJsonOutputEnabled(true);
    //     text.setThinkingEnabled(true);
    //     text.setReasoningEffort("high");
    //     fixture.rows.put("TEXT_GENERATION", text);
    //
    //     AiModelEffectiveConfig config = fixture.service.effectiveConfig(AiModelScope.TEXT_GENERATION);
    //
    //     assertThat(config.provider()).isEqualTo("kimi");
    //     assertThat(config.baseUrl()).isEqualTo("https://api.moonshot.cn/v1/chat/completions");
    //     assertThat(config.apiKey()).isEqualTo("kimi-key");
    //     assertThat(config.apiKeyEnvName()).isEqualTo("KIMI_API_KEY");
    // }

    // Kimi, retired on 2026-10-29:
    // @Test
    // void missingKimiTestConfigDoesNotUseDeepSeekFallbackKey() {
    //     Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "deepseek-key"));
    //
    //     var response = fixture.service.testConfig("TEXT_GENERATION", new AiModelConfigTestRequest(
    //             true,
    //             "moonshot",
    //             "https://api.moonshot.ai/v1/chat/completions",
    //             "kimi-k2.6",
    //             "CLEAR",
    //             null,
    //             true,
    //             true,
    //             "high",
    //             null,
    //             null,
    //             null,
    //             "return ok",
    //             null
    //     ), 9L);
    //
    //     assertThat(response.success()).isFalse();
    //     assertThat(response.provider()).isEqualTo("kimi");
    //     assertThat(response.errorMessage()).contains("API key is not configured");
    //     verify(fixture.completionClient, never()).complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean());
    // }

    @Test
    void disabledProblemDraftTestInheritsDefaultTextGenerationConfig() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "deepseek-key"));
        when(fixture.completionClient.complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn(new ModelCompletions.Result("{\"ok\":true}", "deepseek", "deepseek-v4-pro", 12, 6));

        var response = fixture.service.testConfig("PROBLEM_DRAFT", new AiModelConfigTestRequest(
                false,
                "deepseek",
                "https://api.deepseek.com/chat/completions",
                "deepseek-flash",
                "CLEAR",
                null,
                true,
                true,
                "max",
                null,
                null,
                null,
                "return ok",
                null
        ), 9L);

        assertThat(response.success()).isTrue();
        assertThat(response.provider()).isEqualTo("deepseek");
        assertThat(response.model()).isEqualTo("deepseek-v4-pro");
        verify(fixture.completionClient).complete(
                argThat(config -> config.scope() == AiModelScope.PROBLEM_DRAFT
                        && "deepseek".equals(config.provider())
                        && "deepseek-v4-pro".equals(config.model())
                        && "deepseek-key".equals(config.apiKey())
                        && "DEEPSEEK_API_KEY".equals(config.apiKeyEnvName())
                        && config.inherited()),
                anyList(),
                anyDouble(),
                anyInt(),
                anyBoolean()
        );
        verify(fixture.quotaService).record(9L, "deepseek", "deepseek-v4-pro", 12, 6, true);
    }

    @Test
    void aStoredSceneOverrideOnARetiredProviderRunsOnTheDefaultDeepSeek() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "deepseek-key", "KIMI_API_KEY", "kimi-key"));
        AiModelConfigEntity draft = new AiModelConfigEntity();
        draft.setId(2L);
        draft.setScope("PROBLEM_DRAFT");
        draft.setEnabled(true);
        draft.setProvider("moonshot");
        draft.setBaseUrl("https://api.moonshot.ai/v1/chat/completions");
        draft.setModel("kimi-k2.6");
        draft.setJsonOutputEnabled(true);
        draft.setThinkingEnabled(true);
        draft.setReasoningEffort("high");
        fixture.rows.put("PROBLEM_DRAFT", draft);

        AiModelEffectiveConfig runtime = fixture.service.effectiveConfig(AiModelScope.PROBLEM_DRAFT);

        assertThat(runtime.provider()).isEqualTo("deepseek");
        assertThat(runtime.model()).isEqualTo("deepseek-v4-pro");
        assertThat(runtime.apiKey()).isEqualTo("deepseek-key");
        assertThat(runtime.inherited()).isTrue();
        assertThat(runtime.source()).isEqualTo("TEXT_GENERATION");
    }

    /** Plan 15.2: a Kimi row from before 2026-10-29 still reads; a call made with it is refused as retired. */
    @Test
    void aStoredKimiRowIsListedAsStoredAndItsCallsFailAsRetired() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "deepseek-key", "KIMI_API_KEY", "kimi-key"));
        AiModelConfigEntity text = new AiModelConfigEntity();
        text.setId(1L);
        text.setScope("TEXT_GENERATION");
        text.setEnabled(true);
        text.setProvider("moonshot");
        text.setBaseUrl("https://api.moonshot.cn/v1/chat/completions");
        text.setModel("kimi-k2.6");
        text.setJsonOutputEnabled(true);
        text.setThinkingEnabled(true);
        text.setReasoningEffort("high");
        fixture.rows.put("TEXT_GENERATION", text);

        AiModelConfigResponse display = fixture.service.listConfigs().stream()
                .filter(item -> "TEXT_GENERATION".equals(item.scope()))
                .findFirst()
                .orElseThrow();
        ModelEndpointConfig endpoint = AiModelConfigEndpointSource.toEndpoint(
                fixture.service.effectiveConfig(AiModelScope.TEXT_GENERATION));
        List<ModelCallRecord> records = new ArrayList<>();

        assertThat(display.provider()).isEqualTo("kimi");
        assertThat(display.model()).isEqualTo("kimi-k2.6");
        assertThat(display.source()).isEqualTo("DATABASE");
        assertThat(display.apiKeyConfigured()).as("KIMI_API_KEY is no longer read").isFalse();
        assertThat(endpoint.provider()).isEqualTo(ProviderId.KIMI);
        try (DefaultModelClient client = new DefaultModelClient(scope -> endpoint, List.of(records::add),
                new ModelClientProperties())) {
            assertThatThrownBy(() -> client.complete(ModelRequest.builder("TEXT_GENERATION").endpoint(endpoint)
                    .message(ChatMessage.user("ping")).profile(CallProfile.COMPLETION).build(), CallOptions.of("test")))
                    .isInstanceOfSatisfying(ModelException.class, error -> {
                        assertThat(error.kind()).isEqualTo(ModelErrorKind.CONFIGURATION_INVALID);
                        assertThat(error.getMessage()).isEqualTo("AI provider kimi was retired on 2026-10-29; use deepseek");
                    });
        }
        assertThat(records).as("the refused call is still counted, under kimi").singleElement().satisfies(record -> {
            assertThat(record.provider()).isEqualTo(ProviderId.KIMI);
            assertThat(record.errorKind()).isEqualTo(ModelErrorKind.CONFIGURATION_INVALID);
            assertThat(record.attempts()).isZero();
        });
    }

    @Test
    void theModelListOfAStoredRetiredProviderSaysWhyItIsEmpty() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "deepseek-key", "KIMI_API_KEY", "kimi-key"));
        AiModelListResponse unsupported = new AiModelListResponse("TEXT_GENERATION", "kimi",
                "https://api.moonshot.cn/v1/chat/completions", false, "", true, "UNSUPPORTED",
                "This provider was retired on 2026-10-29; text scopes use deepseek.", java.util.List.of());
        when(fixture.modelCatalogClient.listModels(AiModelScope.TEXT_GENERATION, "kimi",
                "https://api.moonshot.cn/v1/chat/completions", null)).thenReturn(unsupported);

        assertThat(fixture.service.listModels("TEXT_GENERATION", "moonshot",
                "https://api.moonshot.cn/v1/chat/completions")).isSameAs(unsupported);
    }

    @Test
    void anEnabledRowOnARetiredProviderIsReportedAtStartupWithoutFailing() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "deepseek-key"));
        AiModelConfigEntity draft = new AiModelConfigEntity();
        draft.setScope("PROBLEM_DRAFT");
        draft.setEnabled(true);
        draft.setProvider("moonshot");
        AiModelConfigEntity memory = new AiModelConfigEntity();
        memory.setScope("MEMORY_EXTRACTION");
        memory.setEnabled(true);
        memory.setProvider("deepseek");
        when(fixture.mapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(draft, memory))
                .thenThrow(new IllegalStateException("database down"));
        Logger logger = (Logger) LoggerFactory.getLogger(AiModelConfigService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            fixture.service.warnAboutRetiredProviders();
            fixture.service.warnAboutRetiredProviders();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).extracting(ILoggingEvent::getLevel).containsOnly(Level.WARN);
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "AI model config PROBLEM_DRAFT is still enabled on provider moonshot, which was retired on 2026-10-29; "
                        + "the scope falls back to the default text configuration",
                // T2.5: the exception's type, not its message.
                "Could not check the AI model configs for retired providers: IllegalStateException");
    }

    @Test
    void unknownTextProviderFallsBackToDefaultDeepSeekAtRuntime() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "deepseek-key"));
        AiModelConfigEntity draft = new AiModelConfigEntity();
        draft.setId(2L);
        draft.setScope("PROBLEM_DRAFT");
        draft.setEnabled(true);
        draft.setProvider("custom");
        draft.setBaseUrl("https://example.test/v1/chat/completions");
        draft.setModel("custom-model");
        draft.setJsonOutputEnabled(true);
        draft.setThinkingEnabled(false);
        draft.setReasoningEffort("high");
        fixture.rows.put("PROBLEM_DRAFT", draft);

        AiModelEffectiveConfig runtime = fixture.service.effectiveConfig(AiModelScope.PROBLEM_DRAFT);

        assertThat(runtime.provider()).isEqualTo("deepseek");
        assertThat(runtime.apiKey()).isEqualTo("deepseek-key");
        assertThat(runtime.inherited()).isTrue();
    }

    @Test
    void saveRejectsUnsupportedTextProvider() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "deepseek-key"));

        assertThatThrownBy(() -> fixture.service.updateConfig("TEXT_GENERATION", new AiModelConfigUpdateRequest(
                true,
                "zhipu",
                "https://example.test/v1/chat/completions",
                "glm-4",
                "CLEAR",
                null,
                true,
                false,
                "high",
                null,
                null,
                null,
                null
        ), 9L)).isInstanceOf(DomainException.class)
                .hasMessage("Unknown text AI provider zhipu; choose deepseek");
    }

    @Test
    void testRejectsMismatchedTextApiAndModel() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "deepseek-key"));

        assertThatThrownBy(() -> fixture.service.testConfig("TEXT_GENERATION", new AiModelConfigTestRequest(
                true,
                "deepseek",
                "https://api.deepseek.com/chat/completions",
                "kimi-k2.6",
                "CLEAR",
                null,
                true,
                false,
                "high",
                null,
                null,
                null,
                "return ok",
                null
        ), 9L)).isInstanceOf(DomainException.class)
                .hasMessageContaining("do not match");

        verify(fixture.completionClient, never()).complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean());
    }

    @Test
    void apiKeyReplaceIsRejectedBecauseKeysComeFromEnvironment() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "environment-key"));

        assertThatThrownBy(() -> fixture.service.updateConfig("PROBLEM_DRAFT", new AiModelConfigUpdateRequest(
                true,
                "deepseek",
                "https://api.deepseek.com/chat/completions",
                "deepseek-v4-pro",
                "REPLACE",
                "draft-secret-key",
                true,
                true,
                "max",
                0.2,
                2048,
                null,
                null
        ), 9L)).isInstanceOf(DomainException.class)
                .hasMessageContaining("environment variables");

        assertThat(fixture.rows).doesNotContainKey("PROBLEM_DRAFT");
    }

    @Test
    void databaseKeyIsIgnoredAndClearedWhenSavingConfig() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "environment-key"));
        AiModelConfigEntity legacy = new AiModelConfigEntity();
        legacy.setId(1L);
        legacy.setScope("TEXT_GENERATION");
        legacy.setEnabled(true);
        legacy.setProvider("deepseek");
        legacy.setBaseUrl("https://api.deepseek.com/chat/completions");
        legacy.setModel("deepseek-v4-pro");
        legacy.setJsonOutputEnabled(true);
        legacy.setThinkingEnabled(false);
        legacy.setReasoningEffort("high");
        legacy.setApiKeyEncrypted("legacy-encrypted-key");
        legacy.setApiKeyPreview("legacy");
        fixture.rows.put("TEXT_GENERATION", legacy);

        AiModelEffectiveConfig beforeSave = fixture.service.effectiveConfig(AiModelScope.TEXT_GENERATION);

        assertThat(beforeSave.apiKey()).isEqualTo("environment-key");
        assertThat(beforeSave.apiKeySource()).isEqualTo("ENVIRONMENT");

        fixture.service.updateConfig("TEXT_GENERATION", new AiModelConfigUpdateRequest(
                true,
                "deepseek",
                "https://api.deepseek.com/chat/completions",
                "deepseek-v4-pro",
                "CLEAR",
                null,
                true,
                false,
                "high",
                null,
                null,
                null,
                null
        ), 9L);

        AiModelEffectiveConfig effective = fixture.service.effectiveConfig(AiModelScope.TEXT_GENERATION);

        assertThat(fixture.rows.get("TEXT_GENERATION").getApiKeyEncrypted()).isNull();
        assertThat(fixture.rows.get("TEXT_GENERATION").getApiKeyPreview()).isNull();
        assertThat(effective.apiKey()).isEqualTo("environment-key");
        assertThat(effective.apiKeySource()).isEqualTo("ENVIRONMENT");
    }

    @Test
    void testConfigSanitizesProviderErrorsAndRecordsFailure() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "temp-secret-key"));
        when(fixture.completionClient.complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean()))
                .thenThrow(new DomainException(ErrorCode.INTERNAL_ERROR, "Provider returned HTTP 401: {\"error\":{\"message\":\"Authentication Fails, Your api key: ****lqO5 is invalid\"}}"));

        var response = fixture.service.testConfig("TEXT_GENERATION", new AiModelConfigTestRequest(
                true,
                "deepseek",
                "https://api.deepseek.com/chat/completions",
                "deepseek-v4-pro",
                "CLEAR",
                null,
                true,
                false,
                "high",
                null,
                null,
                null,
                "return ok",
                null
        ), 9L);

        assertThat(response.success()).isFalse();
        // Plan S7: the category only; the provider's words (and the key they echo) stay out.
        assertThat(response.errorMessage()).isEqualTo("ai.keyInvalid");
        verify(fixture.quotaService).record(9L, "deepseek", "deepseek-v4-pro", 0, 0, false);
    }

    @Test
    void listModelsUsesEnvironmentKeyForSelectedProvider() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "deepseek-key"));
        AiModelListResponse expected = new AiModelListResponse(
                "TEXT_GENERATION",
                "deepseek",
                "https://api.deepseek.com/chat/completions",
                true,
                "DEEPSEEK_API_KEY",
                true,
                "SUCCESS",
                null,
                java.util.List.of()
        );
        when(fixture.modelCatalogClient.listModels(
                eq(AiModelScope.TEXT_GENERATION),
                eq("deepseek"),
                eq("https://api.deepseek.com/chat/completions"),
                argThat(key -> key != null && "deepseek-key".equals(key.apiKey()) && "DEEPSEEK_API_KEY".equals(key.apiKeyEnvName()))
        )).thenReturn(expected);

        var response = fixture.service.listModels("TEXT_GENERATION", "deepseek", "https://api.deepseek.com/chat/completions");

        assertThat(response).isSameAs(expected);
    }

    // Kimi, retired on 2026-10-29:
    // @Test
    // void listModelsNormalizesKimiBaseUrlToCnEndpoint() {
    //     Fixture fixture = new Fixture(Map.of("KIMI_API_KEY", "kimi-key"));
    //     AiModelListResponse expected = new AiModelListResponse(
    //             "TEXT_GENERATION",
    //             "kimi",
    //             "https://api.moonshot.cn/v1/chat/completions",
    //             true,
    //             "KIMI_API_KEY",
    //             true,
    //             "SUCCESS",
    //             null,
    //             java.util.List.of()
    //     );
    //     when(fixture.modelCatalogClient.listModels(
    //             eq(AiModelScope.TEXT_GENERATION),
    //             eq("kimi"),
    //             eq("https://api.moonshot.cn/v1/chat/completions"),
    //             argThat(key -> key != null && "kimi-key".equals(key.apiKey()) && "KIMI_API_KEY".equals(key.apiKeyEnvName()))
    //     )).thenReturn(expected);
    //
    //     var response = fixture.service.listModels("TEXT_GENERATION", "kimi", "https://api.moonshot.ai/v1/chat/completions");
    //
    //     assertThat(response).isSameAs(expected);
    // }

    @Test
    void saveStoresTheChosenProviderByItsRealName() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "deepseek-key", "AI_EMBEDDING_API_KEY", "embedding-key"));

        fixture.service.updateConfig("TEXT_GENERATION", update("DeepSeek",
                "https://api.deepseek.com/chat/completions", "deepseek-flash", null), 9L);
        fixture.service.updateConfig("EMBEDDING", update("qwen",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "text-embedding-v4", null), 9L);

        assertThat(fixture.rows.get("TEXT_GENERATION").getProvider()).isEqualTo("deepseek");
        assertThat(fixture.rows.get("EMBEDDING").getProvider()).isEqualTo("dashscope");

        fixture.service.updateConfig("EMBEDDING", update("custom",
                "https://llm.example.test/v1/embeddings", "bge-m3", null), 9L);

        assertThat(fixture.rows.get("EMBEDDING").getProvider()).isEqualTo("openai-compatible");
        AiModelEffectiveConfig compatible = fixture.service.effectiveConfig(AiModelScope.EMBEDDING);
        assertThat(compatible.apiKeyEnvName()).isEqualTo("AI_EMBEDDING_API_KEY");
        assertThat(compatible.hasApiKey()).isTrue();
    }

    @Test
    void saveRefusesAProviderTheScopeCannotUseOrThatWasRetired() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "deepseek-key", "KIMI_API_KEY", "kimi-key",
                "OPENAI_COMPATIBLE_API_KEY", "compatible-key"));

        assertThatThrownBy(() -> fixture.service.updateConfig("TEXT_GENERATION", update("moonshot",
                "https://api.moonshot.cn/v1/chat/completions", "kimi-k3", null), 9L))
                .isInstanceOf(DomainException.class)
                .hasMessage("Text AI provider kimi was retired on 2026-10-29; choose deepseek");
        assertThatThrownBy(() -> fixture.service.updateConfig("MEMORY_EXTRACTION", update("openai",
                "https://llm.example.test/v1/chat/completions", "glm-4", null), 9L))
                .hasMessage("Text AI provider openai-compatible was retired on 2026-10-29; choose deepseek");
        assertThatThrownBy(() -> fixture.service.testConfig("PROBLEM_DRAFT", new AiModelConfigTestRequest(true,
                "kimi", "https://api.moonshot.cn/v1/chat/completions", "kimi-k3", "KEEP", null, true, false, "high",
                null, null, null, "return ok", null), 9L))
                .hasMessage("Text AI provider kimi was retired on 2026-10-29; choose deepseek");
        assertThatThrownBy(() -> fixture.service.updateConfig("EMBEDDING", update("deepseek",
                "https://api.deepseek.com", "deepseek-embed", null), 9L))
                .hasMessage("Unknown embedding AI provider deepseek; choose dashscope or openai-compatible");
        assertThat(fixture.rows).isEmpty();
        verify(fixture.completionClient, never()).complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean());
    }

    /** DeepSeek's effort levels are low, high and max (2026-10-29); anything else is saved as high. */
    @Test
    void saveKeepsTheLowEffortAndTurnsAnUnknownOneIntoHigh() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "deepseek-key"));

        fixture.service.updateConfig("TEXT_GENERATION", effort("low"), 9L);
        assertThat(fixture.rows.get("TEXT_GENERATION").getReasoningEffort()).isEqualTo("low");
        assertThat(fixture.service.effectiveConfig(AiModelScope.TEXT_GENERATION).reasoningEffort()).isEqualTo("low");

        fixture.service.updateConfig("TEXT_GENERATION", effort("medium"), 9L);
        assertThat(fixture.rows.get("TEXT_GENERATION").getReasoningEffort()).isEqualTo("high");
    }

    @Test
    void theEnvironmentDefaultTakesTheLowEffortToo() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "deepseek-key"));
        fixture.properties.getDeepseek().setThinkingEnabled(true);
        fixture.properties.getDeepseek().setReasoningEffort("LOW");

        assertThat(fixture.service.effectiveConfig(AiModelScope.TEXT_GENERATION).reasoningEffort()).isEqualTo("low");
    }

    @Test
    void callSettingsAreCheckedStoredUsedAtOnceAndClearedBySaving() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "deepseek-key"));
        AiModelCallSettings settings = new AiModelCallSettings(3_000, 20_000, 90_000, 3, 1, false, true);

        AiModelConfigResponse saved = fixture.service.updateConfig("TEXT_GENERATION", update("deepseek",
                "https://api.deepseek.com/chat/completions", "deepseek-v4-pro", settings), 9L);

        assertThat(saved.callSettings()).isEqualTo(settings);
        EndpointTuning tuning = fixture.service.effectiveConfig(AiModelScope.TEXT_GENERATION).tuning();
        assertThat(tuning).isEqualTo(new EndpointTuning(Duration.ofSeconds(3), Duration.ofSeconds(20),
                Duration.ofSeconds(90), 3, 1, false, true));
        assertThat(AiModelConfigEndpointSource.toEndpoint(fixture.service.effectiveConfig(AiModelScope.TEXT_GENERATION))
                .tuning()).isEqualTo(tuning);
        assertThat(fixture.service.effectiveConfig(AiModelScope.PROBLEM_DRAFT).tuning())
                .as("an inherited scope keeps its own defaults").isEqualTo(EndpointTuning.DEFAULTS);

        fixture.service.updateConfig("TEXT_GENERATION", update("deepseek",
                "https://api.deepseek.com/chat/completions", "deepseek-v4-pro", null), 9L);

        assertThat(fixture.rows.get("TEXT_GENERATION").getTotalTimeoutMs()).isNull();
        assertThat(fixture.rows.get("TEXT_GENERATION").getThinkingFallbackEnabled()).isNull();
        assertThat(fixture.service.effectiveConfig(AiModelScope.TEXT_GENERATION).tuning())
                .isEqualTo(EndpointTuning.DEFAULTS);
        assertThatThrownBy(() -> fixture.service.updateConfig("TEXT_GENERATION", update("deepseek",
                "https://api.deepseek.com/chat/completions", "deepseek-v4-pro",
                new AiModelCallSettings(null, null, null, 0, null, null, null)), 9L))
                .hasMessage("maxConcurrency must be between 1 and 64");
    }

    @Test
    void theConnectivityTestUsesTheFormsCallSettings() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "deepseek-key"));
        when(fixture.completionClient.complete(any(), any(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn(new ModelCompletions.Result("{}", "deepseek", "deepseek-v4-pro", 3, 2));

        fixture.service.testConfig("TEXT_GENERATION", new AiModelConfigTestRequest(true, "deepseek",
                "https://api.deepseek.com/chat/completions", "deepseek-v4-pro", "KEEP", null, true, false, "high",
                null, null, null, null, new AiModelCallSettings(null, null, 45_000, null, 0, null, null)), 9L);

        ArgumentCaptor<AiModelEffectiveConfig> tested = ArgumentCaptor.forClass(AiModelEffectiveConfig.class);
        verify(fixture.completionClient).complete(tested.capture(), any(), anyDouble(), anyInt(), anyBoolean());
        assertThat(tested.getValue().tuning().totalTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(tested.getValue().tuning().maxRetries()).isZero();
    }

    /** Text-level checks of the additive V71 script: nullable columns only, nothing for secrets. */
    @Test
    void v71OnlyAddsNullableColumns() throws IOException {
        String sql;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V71__ai_model_config_call_settings.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        String ddl = sql.replaceAll("(?m)^\\s*--.*$", "");

        assertThat(ddl).contains("alter table ai_model_configs");
        assertThat(ddl.split("add column", -1)).hasSize(8);
        assertThat(ddl).doesNotContain("drop ", "modify ", "change ", "rename ", "not null", "default ",
                "delete from", "insert into", "update ", "create table", "api_key", "secret", "token");
    }

    private static AiModelConfigUpdateRequest update(String provider, String baseUrl, String model,
                                                     AiModelCallSettings settings) {
        return new AiModelConfigUpdateRequest(true, provider, baseUrl, model, "KEEP", null, true, false, "high",
                null, null, null, settings);
    }

    private static AiModelConfigUpdateRequest effort(String effort) {
        return new AiModelConfigUpdateRequest(true, "deepseek", "https://api.deepseek.com/chat/completions",
                "deepseek-flash", "KEEP", null, true, true, effort, null, null, null, null);
    }

    private static final class Fixture {
        final AiProperties properties = new AiProperties();
        final AiModelConfigMapper mapper = mock(AiModelConfigMapper.class);
        final ModelCompletions completionClient = mock(ModelCompletions.class);
        final ModelCatalogService modelCatalogClient = mock(ModelCatalogService.class);
        final AiQuotaService quotaService = mock(AiQuotaService.class);
        final Map<String, AiModelConfigEntity> rows = new HashMap<>();
        final Map<String, String> env = new HashMap<>();
        final AiModelConfigService service;

        Fixture() {
            this(Map.of());
        }

        Fixture(Map<String, String> initialEnv) {
            env.putAll(initialEnv);
            when(mapper.selectOne(any(QueryWrapper.class))).thenAnswer(invocation -> rows.get(scopeFrom(invocation.getArgument(0))));
            when(mapper.insert(any(AiModelConfigEntity.class))).thenAnswer(invocation -> {
                AiModelConfigEntity entity = invocation.getArgument(0);
                rows.put(entity.getScope(), entity);
                return 1;
            });
            when(mapper.updateById(any(AiModelConfigEntity.class))).thenAnswer(invocation -> {
                AiModelConfigEntity entity = invocation.getArgument(0);
                rows.put(entity.getScope(), entity);
                return 1;
            });
            service = new AiModelConfigService(properties, mapper, completionClient, modelCatalogClient, quotaService, name -> env.getOrDefault(name, ""));
        }

        private String scopeFrom(QueryWrapper<AiModelConfigEntity> wrapper) {
            AtomicReference<String> scope = new AtomicReference<>();
            wrapper.getSqlSegment();
            wrapper.getParamNameValuePairs().values().stream()
                    .findFirst()
                    .ifPresent(value -> scope.set(String.valueOf(value)));
            return scope.get();
        }
    }
}
