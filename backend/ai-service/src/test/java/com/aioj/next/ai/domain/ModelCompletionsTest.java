package com.aioj.next.ai.domain;

import com.aioj.next.ai.model.api.CallProfile;
import com.aioj.next.ai.model.api.EmbeddingResponse;
import com.aioj.next.ai.model.api.ModelClient;
import com.aioj.next.ai.model.api.ModelRequest;
import com.aioj.next.ai.model.api.ModelResponse;
import com.aioj.next.ai.model.api.ModelUsage;
import com.aioj.next.ai.model.api.ProviderCapabilities;
import com.aioj.next.ai.model.api.ProviderId;
import com.aioj.next.ai.model.api.ResponseFormat;
import com.aioj.next.ai.model.provider.DeepSeekAdapter;
// Kimi, retired on 2026-10-29: import com.aioj.next.ai.model.provider.KimiAdapter;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.aioj.next.ai.model.api.EndpointTuning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModelCompletionsTest {
    private static final List<Map<String, String>> MESSAGES = List.of(
            Map.of("role", "system", "content", "Return a strict JSON object only."),
            Map.of("role", "user", "content", "ping"));

    private final ModelClient modelClient = mock(ModelClient.class);
    private final ModelCompletions completions = new ModelCompletions(modelClient);

    @Test
    void sendsTheCallersSettingsAndAJsonObjectWhereTheConfigurationAllowsIt() {
        when(modelClient.capabilities(any())).thenReturn(new DeepSeekAdapter().capabilities("deepseek-v4-pro"));
        when(modelClient.complete(any(), any())).thenReturn(answer("{\"ok\":true}", new ModelUsage(12, 3, 0, true)));

        ModelCompletions.Result result = completions.complete(config("deepseek", "deepseek-v4-pro", false, true),
                MESSAGES, 0.1, 1800, true);

        ArgumentCaptor<ModelRequest> request = ArgumentCaptor.forClass(ModelRequest.class);
        verify(modelClient).complete(request.capture(), any());
        assertThat(request.getValue().scope()).isEqualTo("ACCOUNT_IMPORT_PARSE");
        assertThat(request.getValue().profile()).isEqualTo(CallProfile.COMPLETION);
        assertThat(request.getValue().temperature()).isEqualTo(0.1);
        assertThat(request.getValue().maxTokens()).isEqualTo(1800);
        assertThat(request.getValue().responseFormat()).isEqualTo(ResponseFormat.jsonObject());
        assertThat(request.getValue().messages()).extracting(message -> message.role().wireName())
                .containsExactly("system", "user");
        assertThat(result).isEqualTo(new ModelCompletions.Result("{\"ok\":true}", "deepseek", "deepseek-v4-pro", 12, 3));
    }

    @Test
    void theJsonSwitchOfTheConfigurationWins() {
        when(modelClient.capabilities(any())).thenReturn(new DeepSeekAdapter().capabilities("deepseek-v4-pro"));
        when(modelClient.complete(any(), any())).thenReturn(answer("plain", new ModelUsage(1, 1, 0, true)));

        completions.complete(config("deepseek", "deepseek-v4-pro", false, false), MESSAGES, 0.2, 512, true);

        ArgumentCaptor<ModelRequest> request = ArgumentCaptor.forClass(ModelRequest.class);
        verify(modelClient).complete(request.capture(), any());
        assertThat(request.getValue().responseFormat()).isEqualTo(ResponseFormat.text());
    }

    @Test
    void aBlankThinkingAnswerIsRetriedOnceWithoutThinkingAndTheUsageAdds() {
        when(modelClient.capabilities(any())).thenReturn(new DeepSeekAdapter().capabilities("deepseek-v4-pro"));
        when(modelClient.complete(any(), any()))
                .thenReturn(answer("", new ModelUsage(100, 2000, 0, true)))
                .thenReturn(answer("answer", new ModelUsage(100, 30, 0, true)));

        ModelCompletions.Result result = completions.complete(config("deepseek", "deepseek-v4-pro", true, true),
                MESSAGES, 0.1, 1200, false);

        ArgumentCaptor<ModelRequest> requests = ArgumentCaptor.forClass(ModelRequest.class);
        verify(modelClient, times(2)).complete(requests.capture(), any());
        assertThat(requests.getAllValues()).extracting(ModelRequest::thinkingDisabled).containsExactly(false, true);
        assertThat(result.content()).isEqualTo("answer");
        assertThat(result.promptTokens()).isEqualTo(200);
        assertThat(result.completionTokens()).isEqualTo(2030);
    }

    /**
     * No DeepSeek model always thinks (Kimi K3 did, until it was retired on 2026-10-29); the capability
     * below is made up to keep the rule covered.
     */
    @Test
    void aBlankAnswerThatCannotDropThinkingFails() {
        when(modelClient.capabilities(any())).thenReturn(new ProviderCapabilities(ProviderId.DEEPSEEK,
                "deepseek-always-thinks", true, false, true, false, false, true, true, false, false, true, true));
        when(modelClient.complete(any(), any())).thenReturn(answer(" ", new ModelUsage(10, 5, 0, true)));

        assertThatThrownBy(() -> completions.complete(config("deepseek", "deepseek-always-thinks", true, true),
                MESSAGES, 0.1, 512, false))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                    assertThat(error.getMessage()).isEqualTo("AI provider returned empty content");
                });
        verify(modelClient, times(1)).complete(any(), any());
    }

    @Test
    void aScopeThatForbidsDroppingThinkingFailsOnABlankAnswer() {
        when(modelClient.capabilities(any())).thenReturn(new DeepSeekAdapter().capabilities("deepseek-v4-pro"));
        when(modelClient.complete(any(), any())).thenReturn(answer(" ", new ModelUsage(10, 5, 0, true)));
        AiModelEffectiveConfig strict = config("deepseek", "deepseek-v4-pro", true, true)
                .withTuning(new EndpointTuning(null, null, null, null, null, false, null));

        assertThatThrownBy(() -> completions.complete(strict, MESSAGES, 0.1, 512, false))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.getMessage()).isEqualTo("AI provider returned empty content"));
        verify(modelClient, times(1)).complete(any(), any());
    }

    @Test
    void missingUsageIsEstimatedFromTheText() {
        when(modelClient.capabilities(any())).thenReturn(new DeepSeekAdapter().capabilities("deepseek-v4-pro"));
        when(modelClient.complete(any(), any())).thenReturn(answer("12345678", ModelUsage.NONE));

        ModelCompletions.Result result = completions.complete(config("deepseek", "deepseek-v4-pro", false, true),
                MESSAGES, 0.1, 512, false);

        assertThat(result.completionTokens()).isEqualTo(2);
        assertThat(result.promptTokens()).isPositive();
    }

    @Test
    void aDisabledConfigurationFailsBeforeAnyCall() {
        AiModelEffectiveConfig disabled = new AiModelEffectiveConfig(AiModelScope.ACCOUNT_IMPORT_PARSE, false, false,
                "test", "deepseek", "https://api.deepseek.com/chat/completions", "sk-test", "", "", "",
                "deepseek-v4-pro", true, false, "high", null, null, null, Instant.now(), null);

        assertThatThrownBy(() -> completions.complete(disabled, MESSAGES, 0.1, 512, true))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        verifyNoInteractions(modelClient);
    }

    @Test
    void anEmbeddingProbeReportsTheVectorSize() {
        when(modelClient.embed(any(), any())).thenReturn(new EmbeddingResponse(List.of(List.of(0.1, 0.2, 0.3)),
                new ModelUsage(5, 0, 0, true), ProviderId.DASHSCOPE, "text-embedding-v4"));
        AiModelEffectiveConfig embedding = new AiModelEffectiveConfig(AiModelScope.EMBEDDING, true, false, "test",
                "dashscope", "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings", "sk-test", "", "", "",
                "text-embedding-v4", true, false, "high", null, null, 3, Instant.now(), null);

        assertThat(completions.embedProbe(embedding, " ")).isEqualTo(new ModelCompletions.EmbeddingProbe(3, 5));
    }

    private static ModelResponse answer(String content, ModelUsage usage) {
        return new ModelResponse(content, null, List.of(), "stop", usage, ProviderId.DEEPSEEK, "deepseek-v4-pro");
    }

    private static AiModelEffectiveConfig config(String provider, String model, boolean thinking, boolean json) {
        return new AiModelEffectiveConfig(AiModelScope.ACCOUNT_IMPORT_PARSE, true, false, "test", provider,
                "https://api.example.test/chat/completions", "sk-test", "", "", "", model, json, thinking, "high",
                null, null, null, Instant.now(), null);
    }
}
