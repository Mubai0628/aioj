package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_model_configs")
public class AiModelConfigEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String scope;
    private String provider;
    private String baseUrl;
    private String model;
    private Boolean enabled;
    private Boolean jsonOutputEnabled;
    private Boolean thinkingEnabled;
    private String reasoningEffort;
    private Double temperature;
    private Integer maxTokens;
    private Integer embeddingDimension;
    private String apiKeyEncrypted;
    private String apiKeyPreview;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    // Plan A6.2: null means the model client's default, so saving must be able to write a null back.
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer connectTimeoutMs;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer firstByteTimeoutMs;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer totalTimeoutMs;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer maxConcurrency;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer maxRetries;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Boolean thinkingFallbackEnabled;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Boolean streamingEnabled;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Boolean getJsonOutputEnabled() {
        return jsonOutputEnabled;
    }

    public void setJsonOutputEnabled(Boolean jsonOutputEnabled) {
        this.jsonOutputEnabled = jsonOutputEnabled;
    }

    public Boolean getThinkingEnabled() {
        return thinkingEnabled;
    }

    public void setThinkingEnabled(Boolean thinkingEnabled) {
        this.thinkingEnabled = thinkingEnabled;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Integer getEmbeddingDimension() {
        return embeddingDimension;
    }

    public void setEmbeddingDimension(Integer embeddingDimension) {
        this.embeddingDimension = embeddingDimension;
    }

    public String getApiKeyEncrypted() {
        return apiKeyEncrypted;
    }

    public void setApiKeyEncrypted(String apiKeyEncrypted) {
        this.apiKeyEncrypted = apiKeyEncrypted;
    }

    public String getApiKeyPreview() {
        return apiKeyPreview;
    }

    public void setApiKeyPreview(String apiKeyPreview) {
        this.apiKeyPreview = apiKeyPreview;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Integer getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(Integer connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public Integer getFirstByteTimeoutMs() {
        return firstByteTimeoutMs;
    }

    public void setFirstByteTimeoutMs(Integer firstByteTimeoutMs) {
        this.firstByteTimeoutMs = firstByteTimeoutMs;
    }

    public Integer getTotalTimeoutMs() {
        return totalTimeoutMs;
    }

    public void setTotalTimeoutMs(Integer totalTimeoutMs) {
        this.totalTimeoutMs = totalTimeoutMs;
    }

    public Integer getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(Integer maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public Integer getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(Integer maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Boolean getThinkingFallbackEnabled() {
        return thinkingFallbackEnabled;
    }

    public void setThinkingFallbackEnabled(Boolean thinkingFallbackEnabled) {
        this.thinkingFallbackEnabled = thinkingFallbackEnabled;
    }

    public Boolean getStreamingEnabled() {
        return streamingEnabled;
    }

    public void setStreamingEnabled(Boolean streamingEnabled) {
        this.streamingEnabled = streamingEnabled;
    }
}
