package com.aioj.next.ai.domain;

import com.aioj.next.ai.model.api.ModelCallListener;
import com.aioj.next.ai.model.api.ModelCallRecord;
import com.aioj.next.ai.persistence.entity.AiModelCallEntity;
import com.aioj.next.ai.persistence.mapper.AiModelCallMapper;
import com.aioj.next.common.error.SafeErrors;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Observes every model call (plan A1.6): one ai_model_calls row and the
 * {@code aioj.ai.model.calls} metrics. It sees no prompt, reasoning, output or
 * code, and it never fails a call: a write error is logged and dropped. The row
 * has its own transaction, so a caller that calls the model inside a transaction
 * and then rolls back (a memory job whose answer does not parse) still leaves its
 * record. Quotas are not touched; callers keep recording one usage row per turn.
 */
@Component
public class ModelCallRecorder implements ModelCallListener {
    private static final Logger log = LoggerFactory.getLogger(ModelCallRecorder.class);
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final AiModelCallMapper mapper;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate ownTransaction;

    public ModelCallRecorder(AiModelCallMapper mapper, MeterRegistry meterRegistry,
                             PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.meterRegistry = meterRegistry;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void onFinish(ModelCallRecord record) {
        String outcome = record.succeeded() ? "SUCCESS" : record.errorKind().name();
        String provider = record.provider() == null ? "none" : record.provider().configName();
        try {
            meterRegistry.counter("aioj.ai.model.calls", "operation", record.operation().name(),
                    "scope", record.scope(), "provider", provider, "outcome", outcome).increment();
            meterRegistry.timer("aioj.ai.model.call.duration", "operation", record.operation().name(),
                    "scope", record.scope(), "provider", provider).record(Duration.ofMillis(record.latencyMillis()));
            AiModelCallEntity entity = entity(record, outcome);
            ownTransaction.executeWithoutResult(status -> mapper.insert(entity));
        } catch (RuntimeException ex) {
            log.warn("Model call observation failed: scope={} feature={} error={}", record.scope(), record.feature(),
                    SafeErrors.describe(ex));
        }
    }

    private static AiModelCallEntity entity(ModelCallRecord record, String outcome) {
        AiModelCallEntity entity = new AiModelCallEntity();
        entity.setOperation(record.operation().name());
        entity.setScope(cut(record.scope(), 32));
        entity.setFeature(cut(record.feature(), 64));
        entity.setProvider(record.provider() == null ? null : record.provider().configName());
        entity.setModel(cut(record.model(), 160));
        entity.setPromptTokens(record.usage().promptTokens());
        entity.setCompletionTokens(record.usage().completionTokens());
        entity.setCacheHitTokens(record.usage().cacheHitTokens());
        entity.setUsageReported(record.usage().reported());
        entity.setLatencyMs(record.latencyMillis());
        entity.setAttempts(record.attempts());
        entity.setOutcome(outcome);
        entity.setCorrelationId(cut(record.correlationId(), 64));
        entity.setSubjectUserId(record.subjectUserId());
        entity.setStartedAt(LocalDateTime.ofInstant(record.startedAt(), ZONE));
        return entity;
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
