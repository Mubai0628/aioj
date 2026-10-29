package com.aioj.next.ai.domain.coderun;

import com.aioj.next.ai.persistence.entity.AiCodeRunSettingsEntity;
import com.aioj.next.ai.persistence.mapper.AiCodeRunSettingsMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.contract.ai.AiCodeRunSettings;
import com.aioj.next.contract.ai.AiCodeRunSettingsUpdateRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Plan 15.5: the run limits an administrator keeps, with their defaults and ranges. */
class CodeRunSettingsServiceTest {
    private final AiCodeRunSettingsMapper mapper = mock(AiCodeRunSettingsMapper.class);
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final CodeRunSettingsService service = new CodeRunSettingsService(mapper, clock::get);

    @Test
    void withoutASavedRowTheDefaultsApply() {
        AiCodeRunSettings settings = service.current();

        assertThat(settings).isEqualTo(new AiCodeRunSettings(true, 1, 10, 300, 2_000, null, null));
    }

    @Test
    void aRunReadsThroughAShortCacheThatSavingClears() {
        service.current();
        service.current();
        verify(mapper, times(1)).selectById(CodeRunSettingsService.ROW_ID);

        clock.addAndGet(CodeRunSettingsService.CACHE_MILLIS + 1);
        service.current();
        verify(mapper, times(2)).selectById(CodeRunSettingsService.ROW_ID);

        AiCodeRunSettings saved = service.update(new AiCodeRunSettingsUpdateRequest(false, 2, 5, 50, 0), 9L);
        AiCodeRunSettingsEntity row = new AiCodeRunSettingsEntity();
        row.setId(1L);
        row.setEnabled(false);
        row.setMaxConcurrency(2);
        row.setPerMinuteLimit(5);
        row.setPerDayLimit(50);
        row.setMinIntervalMs(0);
        when(mapper.selectById(CodeRunSettingsService.ROW_ID)).thenReturn(row);

        assertThat(saved.enabled()).isFalse();
        assertThat(saved.updatedBy()).isEqualTo(9L);
        assertThat(service.current().maxConcurrency()).as("read again right after saving").isEqualTo(2);
        assertThat(service.current().minIntervalMillis()).isZero();
    }

    @Test
    void theFirstSaveInsertsTheRowAndLaterOnesUpdateIt() {
        service.update(new AiCodeRunSettingsUpdateRequest(true, 1, 10, 300, 2_000), 9L);
        ArgumentCaptor<AiCodeRunSettingsEntity> inserted = ArgumentCaptor.forClass(AiCodeRunSettingsEntity.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getId()).isEqualTo(1L);
        assertThat(inserted.getValue().getUpdatedAt()).isNotNull();

        when(mapper.selectById(CodeRunSettingsService.ROW_ID)).thenReturn(inserted.getValue());
        service.update(new AiCodeRunSettingsUpdateRequest(true, 3, 20, 400, 1_000), 10L);

        verify(mapper).updateById(inserted.getValue());
        assertThat(inserted.getValue().getMaxConcurrency()).isEqualTo(3);
        assertThat(inserted.getValue().getUpdatedBy()).isEqualTo(10L);
    }

    @Test
    void everyFieldIsRequiredAndWithinItsRange() {
        assertThatThrownBy(() -> service.update(new AiCodeRunSettingsUpdateRequest(null, 1, 10, 300, 2_000), 9L))
                .isInstanceOf(DomainException.class).hasMessage("enabled is required");
        assertThatThrownBy(() -> service.update(new AiCodeRunSettingsUpdateRequest(true, 0, 10, 300, 2_000), 9L))
                .hasMessage("maxConcurrency must be between 1 and 16");
        assertThatThrownBy(() -> service.update(new AiCodeRunSettingsUpdateRequest(true, 1, 601, 300, 2_000), 9L))
                .hasMessage("perMinuteLimit must be between 1 and 600");
        assertThatThrownBy(() -> service.update(new AiCodeRunSettingsUpdateRequest(true, 1, 10, null, 2_000), 9L))
                .hasMessage("perDayLimit must be between 1 and 100000");
        assertThatThrownBy(() -> service.update(new AiCodeRunSettingsUpdateRequest(true, 1, 10, 300, 60_001), 9L))
                .hasMessage("minIntervalMillis must be between 0 and 60000");
        verify(mapper, never()).insert(any(AiCodeRunSettingsEntity.class));
        verify(mapper, never()).updateById(any(AiCodeRunSettingsEntity.class));
    }

    /** Text-level checks of V73: one new table, and only text scopes on retired providers switched off. */
    @Test
    void v73AddsTheTableAndOnlySwitchesOffRetiredTextProviders() throws Exception {
        String sql;
        try (var stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V73__ai_code_run_settings_and_retired_text_providers.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).toLowerCase();
        }
        String ddl = sql.replaceAll("(?m)^\\s*--.*$", "");

        assertThat(ddl).contains("create table if not exists ai_code_run_settings");
        assertThat(ddl.split("create table", -1)).hasSize(2);
        assertThat(ddl.split("update ", -1)).hasSize(2);
        assertThat(ddl).contains("update ai_model_configs", "set enabled = 0", "scope <> 'embedding'",
                "in ('kimi', 'moonshot', 'openai-compatible', 'openai', 'custom')");
        assertThat(ddl).doesNotContain("drop ", "delete from", "alter table", "api_key", "insert into");
    }
}
