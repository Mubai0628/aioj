-- Per-scope call settings of the model call package (Agent refactor plan T1.4, A6.2).
-- Columns only: every one is nullable and NULL means the aioj.ai.model-client default,
-- so existing rows keep today's behaviour. API keys stay in the server environment.
ALTER TABLE ai_model_configs
    ADD COLUMN connect_timeout_ms INT NULL,
    ADD COLUMN first_byte_timeout_ms INT NULL,
    ADD COLUMN total_timeout_ms INT NULL,
    ADD COLUMN max_concurrency INT NULL,
    ADD COLUMN max_retries INT NULL,
    ADD COLUMN thinking_fallback_enabled TINYINT(1) NULL,
    ADD COLUMN streaming_enabled TINYINT(1) NULL;
