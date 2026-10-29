package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** Plan 15.5: the self-test run's limits (V73); one row, id 1. */
@TableName("ai_code_run_settings")
public class AiCodeRunSettingsEntity {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Boolean enabled;
    private Integer maxConcurrency;
    private Integer perMinuteLimit;
    private Integer perDayLimit;
    private Integer minIntervalMs;
    private LocalDateTime updatedAt;
    private Long updatedBy;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(Integer maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public Integer getPerMinuteLimit() {
        return perMinuteLimit;
    }

    public void setPerMinuteLimit(Integer perMinuteLimit) {
        this.perMinuteLimit = perMinuteLimit;
    }

    public Integer getPerDayLimit() {
        return perDayLimit;
    }

    public void setPerDayLimit(Integer perDayLimit) {
        this.perDayLimit = perDayLimit;
    }

    public Integer getMinIntervalMs() {
        return minIntervalMs;
    }

    public void setMinIntervalMs(Integer minIntervalMs) {
        this.minIntervalMs = minIntervalMs;
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
}
