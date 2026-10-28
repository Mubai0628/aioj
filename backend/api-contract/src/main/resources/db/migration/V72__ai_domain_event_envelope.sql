-- Plan T1.7 (A3.3): the channel's event envelope on the existing event log. Only adds
-- nullable columns: rows written before T1.7 keep NULL (schema version 1, producer and trace
-- unknown). Subjects stay in user_id and source_type / source_id.
ALTER TABLE ai_domain_events
    ADD COLUMN schema_version INT NULL AFTER event_type,
    ADD COLUMN producer VARCHAR(64) NULL AFTER schema_version,
    ADD COLUMN trace_id VARCHAR(64) NULL AFTER sensitivity;
