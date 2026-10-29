package com.aioj.next.ai.domain.coderun;

import com.aioj.next.ai.persistence.entity.AiCodeRunSettingsEntity;
import com.aioj.next.ai.persistence.mapper.AiCodeRunSettingsMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiCodeRunSettings;
import com.aioj.next.contract.ai.AiCodeRunSettingsUpdateRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.function.LongSupplier;

/**
 * Plan 15.5: the self-test run's limits, kept by an administrator in ai_code_run_settings (V73).
 * Until one saves them the defaults apply. A run reads them through a short cache; saving clears it.
 */
@Service
public class CodeRunSettingsService {
    static final long ROW_ID = 1L;
    /** On; one run at a time site-wide; 10 a minute and 300 a day per person; 2 s between two runs of one. */
    static final AiCodeRunSettings DEFAULTS = new AiCodeRunSettings(true, 1, 10, 300, 2_000, null, null);
    static final long CACHE_MILLIS = 10_000;

    private final AiCodeRunSettingsMapper mapper;
    private final LongSupplier clock;
    private volatile Cached cached;

    @Autowired
    public CodeRunSettingsService(AiCodeRunSettingsMapper mapper) {
        this(mapper, System::currentTimeMillis);
    }

    CodeRunSettingsService(AiCodeRunSettingsMapper mapper, LongSupplier clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public AiCodeRunSettings current() {
        long now = clock.getAsLong();
        Cached snapshot = cached;
        if (snapshot != null && snapshot.expiresAtMillis() > now) {
            return snapshot.settings();
        }
        AiCodeRunSettingsEntity row = mapper.selectById(ROW_ID);
        AiCodeRunSettings settings = row == null ? DEFAULTS : toSettings(row);
        cached = new Cached(settings, now + CACHE_MILLIS);
        return settings;
    }

    @Transactional
    public AiCodeRunSettings update(AiCodeRunSettingsUpdateRequest request, Long updatedBy) {
        if (request == null || request.enabled() == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "enabled is required");
        }
        int maxConcurrency = within("maxConcurrency", request.maxConcurrency(), 1, 16);
        int perMinute = within("perMinuteLimit", request.perMinuteLimit(), 1, 600);
        int perDay = within("perDayLimit", request.perDayLimit(), 1, 100_000);
        int interval = within("minIntervalMillis", request.minIntervalMillis(), 0, 60_000);
        AiCodeRunSettingsEntity row = mapper.selectById(ROW_ID);
        boolean insert = row == null;
        if (insert) {
            row = new AiCodeRunSettingsEntity();
            row.setId(ROW_ID);
        }
        row.setEnabled(request.enabled());
        row.setMaxConcurrency(maxConcurrency);
        row.setPerMinuteLimit(perMinute);
        row.setPerDayLimit(perDay);
        row.setMinIntervalMs(interval);
        row.setUpdatedAt(LocalDateTime.now());
        row.setUpdatedBy(updatedBy);
        if (insert) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
        cached = null;
        return toSettings(row);
    }

    private static int within(String field, Integer value, int min, int max) {
        if (value == null || value < min || value > max) {
            throw new DomainException(ErrorCode.BAD_REQUEST, field + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static AiCodeRunSettings toSettings(AiCodeRunSettingsEntity row) {
        return new AiCodeRunSettings(
                Boolean.TRUE.equals(row.getEnabled()),
                orDefault(row.getMaxConcurrency(), DEFAULTS.maxConcurrency()),
                orDefault(row.getPerMinuteLimit(), DEFAULTS.perMinuteLimit()),
                orDefault(row.getPerDayLimit(), DEFAULTS.perDayLimit()),
                row.getMinIntervalMs() == null ? DEFAULTS.minIntervalMillis() : Math.max(0, row.getMinIntervalMs()),
                row.getUpdatedAt() == null ? null : row.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant(),
                row.getUpdatedBy());
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null || value < 1 ? fallback : value;
    }

    private record Cached(AiCodeRunSettings settings, long expiresAtMillis) {
    }
}
