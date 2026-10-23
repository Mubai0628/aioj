package com.aioj.next.ai.domain;

import com.aioj.next.ai.model.api.ModelCallRecord;
import com.aioj.next.ai.model.api.ModelErrorKind;
import com.aioj.next.ai.model.api.ModelOperation;
import com.aioj.next.ai.model.api.ModelUsage;
import com.aioj.next.ai.model.api.ProviderId;
import com.aioj.next.ai.persistence.entity.AiModelCallEntity;
import com.aioj.next.ai.persistence.mapper.AiModelCallMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelCallRecorderTest {
    private final AiModelCallMapper mapper = mock(AiModelCallMapper.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ModelCallRecorder recorder = new ModelCallRecorder(mapper, registry);

    @Test
    void writesOneRowWithTheUsageAndCountsTheCall() {
        recorder.onFinish(new ModelCallRecord(ModelOperation.COMPLETE, "TEXT_GENERATION", "agent.chat_stream",
                ProviderId.DEEPSEEK, "deepseek-v4-pro", new ModelUsage(1200, 80, 1024, true), 950, 1, null,
                "turn-1", 42L, Instant.parse("2026-10-23T06:00:00Z")));

        ArgumentCaptor<AiModelCallEntity> row = ArgumentCaptor.forClass(AiModelCallEntity.class);
        verify(mapper).insert(row.capture());
        AiModelCallEntity entity = row.getValue();
        assertThat(entity.getOperation()).isEqualTo("COMPLETE");
        assertThat(entity.getScope()).isEqualTo("TEXT_GENERATION");
        assertThat(entity.getFeature()).isEqualTo("agent.chat_stream");
        assertThat(entity.getProvider()).isEqualTo("deepseek");
        assertThat(entity.getModel()).isEqualTo("deepseek-v4-pro");
        assertThat(entity.getPromptTokens()).isEqualTo(1200);
        assertThat(entity.getCompletionTokens()).isEqualTo(80);
        assertThat(entity.getCacheHitTokens()).isEqualTo(1024);
        assertThat(entity.getUsageReported()).isTrue();
        assertThat(entity.getLatencyMs()).isEqualTo(950);
        assertThat(entity.getAttempts()).isEqualTo(1);
        assertThat(entity.getOutcome()).isEqualTo("SUCCESS");
        assertThat(entity.getCorrelationId()).isEqualTo("turn-1");
        assertThat(entity.getSubjectUserId()).isEqualTo(42L);
        assertThat(registry.get("aioj.ai.model.calls").tag("outcome", "SUCCESS").tag("provider", "deepseek")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void aFailedCallIsRecordedWithItsKindEvenWithoutAProvider() {
        recorder.onFinish(new ModelCallRecord(ModelOperation.EMBED, "EMBEDDING", "retrieval.embedding", null, null,
                ModelUsage.NONE, 3, 0, ModelErrorKind.CONFIGURATION_INVALID, null, null, Instant.now()));

        ArgumentCaptor<AiModelCallEntity> row = ArgumentCaptor.forClass(AiModelCallEntity.class);
        verify(mapper).insert(row.capture());
        assertThat(row.getValue().getOutcome()).isEqualTo("CONFIGURATION_INVALID");
        assertThat(row.getValue().getProvider()).isNull();
        assertThat(row.getValue().getUsageReported()).isFalse();
        assertThat(registry.get("aioj.ai.model.calls").tag("provider", "none").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aWriteFailureNeverReachesTheCall() {
        when(mapper.insert(any(AiModelCallEntity.class))).thenThrow(new IllegalStateException("database down"));

        assertThatCode(() -> recorder.onFinish(new ModelCallRecord(ModelOperation.STREAM, "TEXT_GENERATION",
                "agent.chat_stream", ProviderId.KIMI, "kimi-k3", ModelUsage.NONE, 10, 1, ModelErrorKind.CANCELLED,
                null, null, Instant.now()))).doesNotThrowAnyException();
    }

    /** Text-level checks of the additive V70 script: one new table, no column that could hold content. */
    @Test
    void v70CreatesOnlyTheObservationTableAndStoresNoContent() throws IOException {
        String sql;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V70__ai_model_calls.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        String ddl = sql.replaceAll("(?m)^\\s*--.*$", "");

        assertThat(ddl).contains("create table ai_model_calls");
        assertThat(ddl).doesNotContain("drop ", "alter table", "delete from", "insert into", "update ");
        assertThat(ddl).doesNotContain("prompt_text", "content", "reasoning", "message", "code ", "text ");
    }
}
