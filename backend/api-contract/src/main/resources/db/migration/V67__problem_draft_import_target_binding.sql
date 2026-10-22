-- Bind a recoverable import saga to the server-derived existing-problem target.
-- This is additive because V66 may already have been applied by local acceptance environments.

ALTER TABLE ai_problem_draft_import_sagas
    ADD COLUMN requested_problem_id BIGINT NULL AFTER acknowledge_unverified,
    ADD INDEX idx_problem_draft_import_requested_problem (requested_problem_id);
