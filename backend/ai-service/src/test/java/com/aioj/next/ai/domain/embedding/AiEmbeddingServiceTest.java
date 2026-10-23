package com.aioj.next.ai.domain.embedding;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.AiCapacityService;
import com.aioj.next.ai.domain.AiModelConfigResolver;
import com.aioj.next.ai.domain.AiModelEffectiveConfig;
import com.aioj.next.ai.domain.AiModelScope;
import com.aioj.next.ai.domain.TextEmbeddings;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiEmbeddingRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiEmbeddingServiceTest {
    private final TextEmbeddings provider = mock(TextEmbeddings.class);
    private final AiModelConfigResolver configResolver = mock(AiModelConfigResolver.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AiEmbeddingService service = new AiEmbeddingService(provider,
            new AiCapacityService(new AiProperties()), configResolver, meters);

    @Test
    void returnsTheVectorWithItsModelAndMetersThePurpose() {
        when(provider.embed("二分 查找")).thenReturn(Optional.of(List.of(0.5, -0.25, 1.0)));
        AiModelEffectiveConfig config = mock(AiModelEffectiveConfig.class);
        when(config.model()).thenReturn("embed-v4");
        when(configResolver.effectiveConfig(AiModelScope.EMBEDDING)).thenReturn(config);

        var response = service.embed(new AiEmbeddingRequest("  二分 查找 ", "PROBLEM_QUERY"));

        assertThat(response.embedding()).containsExactly(0.5f, -0.25f, 1.0f);
        assertThat(response.model()).isEqualTo("embed-v4");
        assertThat(response.dimension()).isEqualTo(3);
        assertThat(meters.counter("aioj.ai.embedding.requests", "purpose", "PROBLEM_QUERY", "outcome", "ok").count())
                .isEqualTo(1.0);
    }

    @Test
    void unavailableProviderIsAServiceErrorAndIsMetered() {
        when(provider.embed(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.embed(new AiEmbeddingRequest("text", "unknown purpose")))
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThat(meters.counter("aioj.ai.embedding.requests", "purpose", "OTHER", "outcome", "unavailable").count())
                .isEqualTo(1.0);
    }

    @Test
    void longInputIsCutToTheProviderLimit() {
        when(provider.embed(anyString())).thenReturn(Optional.of(List.of(1.0)));
        AiModelEffectiveConfig config = mock(AiModelEffectiveConfig.class);
        when(configResolver.effectiveConfig(AiModelScope.EMBEDDING)).thenReturn(config);

        service.embed(new AiEmbeddingRequest("x".repeat(AiEmbeddingService.MAX_INPUT_CHARS + 50), "PROBLEM_INDEX"));

        verify(provider).embed("x".repeat(AiEmbeddingService.MAX_INPUT_CHARS));
    }
}
