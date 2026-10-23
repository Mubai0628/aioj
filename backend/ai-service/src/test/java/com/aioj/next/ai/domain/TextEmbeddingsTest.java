package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.model.api.EmbeddingRequest;
import com.aioj.next.ai.model.api.EmbeddingResponse;
import com.aioj.next.ai.model.api.ModelClient;
import com.aioj.next.ai.model.api.ModelErrorKind;
import com.aioj.next.ai.model.api.ModelException;
import com.aioj.next.ai.model.api.ModelUsage;
import com.aioj.next.ai.model.api.ProviderId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TextEmbeddingsTest {
    private final ModelClient modelClient = mock(ModelClient.class);

    @BeforeEach
    void resetCounters() {
        AiFailureMetrics.reset();
    }

    @Test
    void embedsTheNormalizedTextWithTheEmbeddingEndpoint() {
        when(modelClient.embed(any(), any())).thenReturn(new EmbeddingResponse(List.of(List.of(0.5, -0.25)),
                new ModelUsage(4, 0, 0, true), ProviderId.DASHSCOPE, "text-embedding-v4"));
        TextEmbeddings embeddings = new TextEmbeddings(modelClient, scope -> config(true, "embed-key"));

        assertThat(embeddings.embed("  二分\r\n查找  ")).contains(List.of(0.5, -0.25));

        ArgumentCaptor<EmbeddingRequest> request = ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(modelClient).embed(request.capture(), any());
        assertThat(request.getValue().inputs()).containsExactly("二分\n查找");
        assertThat(request.getValue().endpoint().provider()).isEqualTo(ProviderId.DASHSCOPE);
        assertThat(request.getValue().endpoint().embeddingDimension()).isEqualTo(1024);
    }

    /** W1.6: a failing embedding is counted and still degrades to no vector. */
    @Test
    void aFailureIsCountedAndStillReturnsEmpty() {
        when(modelClient.embed(any(), any())).thenThrow(new ModelException(ModelErrorKind.UNAVAILABLE,
                ProviderId.DASHSCOPE, "text-embedding-v4", "AI provider dashscope is unavailable (HTTP 503)"));
        TextEmbeddings embeddings = new TextEmbeddings(modelClient, scope -> config(true, "embed-key"));

        assertThat(embeddings.embed("测试文本")).isEmpty();
        assertThat(AiFailureMetrics.embeddingFailures()).isEqualTo(1);
    }

    @Test
    void disabledOrKeylessEmbeddingsDoNotCallTheModel() {
        assertThat(new TextEmbeddings(modelClient, scope -> config(false, "embed-key")).embed("x")).isEmpty();
        assertThat(new TextEmbeddings(modelClient, scope -> config(true, "")).embed("x")).isEmpty();
        assertThat(new TextEmbeddings(modelClient, scope -> config(true, "embed-key")).embed("  ")).isEmpty();
        verifyNoInteractions(modelClient);
        assertThat(AiFailureMetrics.embeddingFailures()).isZero();
    }

    /** The DeepSeek key is never borrowed for embeddings. */
    @Test
    void theGlobalDeepSeekKeyIsNotUsedForEmbeddings() {
        AiProperties properties = new AiProperties();
        properties.setApiKey("deepseek-key");
        TextEmbeddings embeddings = new TextEmbeddings(modelClient,
                new EnvironmentAiModelConfigResolver(properties, ignored -> ""));

        assertThat(embeddings.embed("test text")).isEmpty();
        verifyNoInteractions(modelClient);
    }

    private static AiModelEffectiveConfig config(boolean enabled, String apiKey) {
        return new AiModelEffectiveConfig(AiModelScope.EMBEDDING, enabled, false, "test", "dashscope",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings", apiKey, "", "", "",
                "text-embedding-v4", true, false, "high", null, null, 1024, Instant.now(), null);
    }
}
