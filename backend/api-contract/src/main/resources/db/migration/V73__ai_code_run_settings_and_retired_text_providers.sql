-- Plan 15.5 (phase 1, batch 6): the limits of the self-test run ("运行"), set on the admin settings
-- page. One row, id 1; while there is none, the run uses its defaults (on, 1 at a time, 10 a minute and
-- 300 a day per person, 2 s between two runs of one person).
CREATE TABLE IF NOT EXISTS ai_code_run_settings (
    id BIGINT PRIMARY KEY,
    enabled TINYINT NOT NULL,
    max_concurrency INT NOT NULL,
    per_minute_limit INT NOT NULL,
    per_day_limit INT NOT NULL,
    min_interval_ms INT NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    updated_by BIGINT NULL
);

-- Plan 15.2: Kimi and the plain OpenAI-compatible text option were retired on 2026-10-29. A text
-- scope stored on one of them is switched off, so it falls back to the default text configuration;
-- the stored provider, model and the rest stay in the row. Embeddings keep openai-compatible.
UPDATE ai_model_configs
SET enabled = 0
WHERE scope <> 'EMBEDDING'
  AND enabled = 1
  AND LOWER(provider) IN ('kimi', 'moonshot', 'openai-compatible', 'openai', 'custom');
