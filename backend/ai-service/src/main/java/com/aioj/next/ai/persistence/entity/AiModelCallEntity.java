package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** One observed model call (V70); never holds prompts, reasoning, outputs or code. */
@TableName("ai_model_calls")
public class AiModelCallEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String operation;
    private String scope;
    private String feature;
    private String provider;
    private String model;
    private Long promptTokens;
    private Long completionTokens;
    private Long cacheHitTokens;
    private Boolean usageReported;
    private Long latencyMs;
    private Integer attempts;
    private String outcome;
    private String correlationId;
    private Long subjectUserId;
    private LocalDateTime startedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getFeature() {
        return feature;
    }

    public void setFeature(String feature) {
        this.feature = feature;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Long getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Long promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Long getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Long completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Long getCacheHitTokens() {
        return cacheHitTokens;
    }

    public void setCacheHitTokens(Long cacheHitTokens) {
        this.cacheHitTokens = cacheHitTokens;
    }

    public Boolean getUsageReported() {
        return usageReported;
    }

    public void setUsageReported(Boolean usageReported) {
        this.usageReported = usageReported;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public Long getSubjectUserId() {
        return subjectUserId;
    }

    public void setSubjectUserId(Long subjectUserId) {
        this.subjectUserId = subjectUserId;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }
}
