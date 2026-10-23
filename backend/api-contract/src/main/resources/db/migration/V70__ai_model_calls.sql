-- One row per call made through the model call package (Agent refactor plan T1.3,
-- A1.6), for observation only. No prompt, reasoning, output or code is stored, and
-- quota accounting stays in ai_usage_records with its one-row-per-turn rule.
-- outcome is SUCCESS or the failure kind; attempts counts retries.
CREATE TABLE ai_model_calls (
    id BIGINT PRIMARY KEY,
    operation VARCHAR(16) NOT NULL,
    scope VARCHAR(32) NOT NULL,
    feature VARCHAR(64) NOT NULL,
    provider VARCHAR(32) NULL,
    model VARCHAR(160) NULL,
    prompt_tokens BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    cache_hit_tokens BIGINT NOT NULL DEFAULT 0,
    usage_reported TINYINT(1) NOT NULL DEFAULT 0,
    latency_ms BIGINT NOT NULL,
    attempts INT NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    correlation_id VARCHAR(64) NULL,
    subject_user_id BIGINT NULL,
    started_at DATETIME(3) NOT NULL,
    INDEX idx_model_calls_started (started_at),
    INDEX idx_model_calls_scope_started (scope, started_at),
    INDEX idx_model_calls_outcome_started (outcome, started_at),
    INDEX idx_model_calls_correlation (correlation_id)
);
