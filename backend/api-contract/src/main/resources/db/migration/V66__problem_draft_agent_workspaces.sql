-- Durable, isolated AI problem-draft Agent workspaces.
-- V49-V51 are historical, unwired structures and intentionally remain untouched.

CREATE TABLE IF NOT EXISTS ai_problem_draft_workspaces (
    id VARCHAR(64) PRIMARY KEY,
    creator_user_id BIGINT NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    candidate_draft_id BIGINT NOT NULL,
    source_draft_id BIGINT NULL,
    current_draft_id BIGINT NULL,
    current_agent_run_id BIGINT NULL,
    current_artifact_id BIGINT NULL,
    active_turn_id VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    stage VARCHAR(64) NOT NULL,
    agent_mode VARCHAR(48) NOT NULL DEFAULT 'THINKING_TOOL_LOOP',
    state_version BIGINT NOT NULL DEFAULT 0,
    working_state_json JSON NOT NULL,
    requested_cf_rating INT NULL,
    review_risk VARCHAR(32) NOT NULL DEFAULT 'STANDARD',
    content_fingerprint CHAR(64) NULL,
    verification_fingerprint CHAR(64) NULL,
    static_validation_json JSON NULL,
    samples_validation_json JSON NULL,
    package_validation_json JSON NULL,
    safe_summary VARCHAR(1000) NULL,
    resource_metrics_json JSON NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_problem_draft_workspace_conversation (conversation_id),
    KEY idx_problem_draft_workspace_owner (creator_user_id, updated_at),
    KEY idx_problem_draft_workspace_status (status, updated_at),
    UNIQUE KEY uk_problem_draft_workspace_candidate (candidate_draft_id),
    KEY idx_problem_draft_workspace_current_draft (current_draft_id)
);

CREATE TABLE IF NOT EXISTS ai_problem_draft_executions (
    id VARCHAR(64) PRIMARY KEY,
    workspace_id VARCHAR(64) NOT NULL,
    creator_user_id BIGINT NOT NULL,
    agent_run_id BIGINT NULL,
    tool_call_id VARCHAR(128) NULL,
    purpose VARCHAR(48) NOT NULL,
    language VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    stdout MEDIUMTEXT NULL,
    stderr MEDIUMTEXT NULL,
    stdout_bytes BIGINT NOT NULL DEFAULT 0,
    stderr_bytes BIGINT NOT NULL DEFAULT 0,
    output_truncated TINYINT(1) NOT NULL DEFAULT 0,
    result_hash CHAR(64) NULL,
    time_millis BIGINT NULL,
    memory_kb BIGINT NULL,
    proc_peak BIGINT NULL,
    exit_status INT NULL,
    run_time_millis BIGINT NULL,
    expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    KEY idx_problem_draft_execution_workspace (workspace_id, created_at),
    KEY idx_problem_draft_execution_expiry (expires_at),
    KEY idx_problem_draft_execution_run (agent_run_id, created_at)
);

CREATE TABLE IF NOT EXISTS ai_problem_draft_testcase_upload_sessions (
    id VARCHAR(64) PRIMARY KEY,
    workspace_id VARCHAR(64) NOT NULL,
    draft_id BIGINT NULL,
    creator_user_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    temp_storage_path VARCHAR(512) NOT NULL,
    expected_size_bytes BIGINT NOT NULL,
    expected_sha256 CHAR(64) NOT NULL,
    uploaded_size_bytes BIGINT NOT NULL DEFAULT 0,
    chunk_size_bytes INT NOT NULL,
    chunk_count INT NOT NULL,
    received_chunk_count INT NOT NULL DEFAULT 0,
    artifact_id BIGINT NULL,
    error_code VARCHAR(96) NULL,
    error_message VARCHAR(1000) NULL,
    expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    KEY idx_problem_draft_upload_workspace (workspace_id, created_at),
    KEY idx_problem_draft_upload_expiry (status, expires_at)
);

CREATE TABLE IF NOT EXISTS ai_problem_draft_testcase_upload_chunks (
    upload_id VARCHAR(64) NOT NULL,
    chunk_index INT NOT NULL,
    size_bytes INT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (upload_id, chunk_index)
);

CREATE TABLE IF NOT EXISTS ai_problem_draft_import_sagas (
    id BIGINT PRIMARY KEY,
    workspace_id VARCHAR(64) NOT NULL,
    draft_id BIGINT NOT NULL,
    artifact_id BIGINT NOT NULL,
    creator_user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    state VARCHAR(32) NOT NULL,
    import_mode VARCHAR(32) NOT NULL,
    target_visibility VARCHAR(16) NOT NULL,
    acknowledge_unverified TINYINT(1) NOT NULL DEFAULT 0,
    problem_id BIGINT NULL,
    testcase_package_id BIGINT NULL,
    artifact_sha256 CHAR(64) NOT NULL,
    verification_fingerprint CHAR(64) NOT NULL,
    last_error_code VARCHAR(96) NULL,
    last_error_message VARCHAR(1000) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    UNIQUE KEY uk_problem_draft_import_idempotency (creator_user_id, idempotency_key),
    KEY idx_problem_draft_import_workspace (workspace_id, state, updated_at),
    KEY idx_problem_draft_import_problem (problem_id)
);

ALTER TABLE ai_problem_draft_generation_jobs
    ADD COLUMN workspace_id VARCHAR(64) NULL AFTER source_draft_id,
    ADD COLUMN turn_id VARCHAR(64) NULL AFTER workspace_id,
    ADD COLUMN agent_run_id BIGINT NULL AFTER turn_id,
    ADD COLUMN state_version BIGINT NOT NULL DEFAULT 0 AFTER agent_run_id,
    ADD COLUMN requested_cf_rating INT NULL AFTER state_version,
    ADD COLUMN review_risk VARCHAR(32) NOT NULL DEFAULT 'STANDARD' AFTER requested_cf_rating,
    ADD COLUMN agent_mode VARCHAR(48) NULL AFTER review_risk,
    ADD COLUMN content_fingerprint CHAR(64) NULL AFTER agent_mode,
    ADD COLUMN verification_fingerprint CHAR(64) NULL AFTER content_fingerprint,
    ADD INDEX idx_problem_draft_jobs_workspace (workspace_id, updated_at),
    ADD UNIQUE KEY uk_problem_draft_jobs_turn (turn_id),
    ADD INDEX idx_problem_draft_jobs_agent_run (agent_run_id);

ALTER TABLE problem_drafts
    ADD COLUMN workspace_id VARCHAR(64) NULL AFTER creator_user_id,
    ADD COLUMN state_version BIGINT NOT NULL DEFAULT 0 AFTER workspace_id,
    ADD COLUMN content_fingerprint CHAR(64) NULL AFTER state_version,
    ADD COLUMN verification_fingerprint CHAR(64) NULL AFTER content_fingerprint,
    ADD COLUMN requested_cf_rating INT NULL AFTER verification_fingerprint,
    ADD COLUMN review_risk VARCHAR(32) NOT NULL DEFAULT 'STANDARD' AFTER requested_cf_rating,
    ADD COLUMN agent_run_id BIGINT NULL AFTER review_risk,
    ADD INDEX idx_problem_drafts_workspace (workspace_id, state_version),
    ADD INDEX idx_problem_drafts_agent_run (agent_run_id);

ALTER TABLE ai_problem_draft_testcase_artifacts
    ADD COLUMN workspace_id VARCHAR(64) NULL AFTER draft_id,
    ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'AGENT_GENERATED' AFTER creator_user_id,
    ADD COLUMN format_status VARCHAR(32) NOT NULL DEFAULT 'READY' AFTER status,
    ADD COLUMN execution_status VARCHAR(32) NOT NULL DEFAULT 'NOT_RUN' AFTER format_status,
    ADD COLUMN verification_mode VARCHAR(32) NULL AFTER execution_status,
    ADD COLUMN verification_fingerprint CHAR(64) NULL AFTER verification_mode,
    ADD COLUMN agent_run_id BIGINT NULL AFTER verification_fingerprint,
    ADD COLUMN failure_summary VARCHAR(1000) NULL AFTER package_summary_json,
    ADD COLUMN generated_at DATETIME(3) NULL AFTER failure_summary,
    ADD COLUMN validated_at DATETIME(3) NULL AFTER generated_at,
    ADD INDEX idx_problem_draft_artifact_workspace (workspace_id, status, created_at),
    ADD INDEX idx_problem_draft_artifact_fingerprint (draft_id, verification_fingerprint);

ALTER TABLE ai_agent_runs
    ADD COLUMN workspace_id VARCHAR(64) NULL AFTER conversation_id,
    ADD COLUMN execution_mode VARCHAR(48) NULL AFTER output_mode,
    ADD COLUMN safe_summary VARCHAR(1000) NULL AFTER execution_mode,
    ADD COLUMN deadline_at DATETIME(3) NULL AFTER safe_summary,
    ADD INDEX idx_agent_run_workspace (workspace_id, started_at);

ALTER TABLE ai_tool_calls
    ADD COLUMN safe_summary_json JSON NULL AFTER result_classification,
    ADD COLUMN resource_ref_type VARCHAR(48) NULL AFTER safe_summary_json,
    ADD COLUMN resource_ref_id VARCHAR(128) NULL AFTER resource_ref_type,
    ADD COLUMN resource_metrics_json JSON NULL AFTER resource_ref_id,
    ADD COLUMN started_at DATETIME(3) NULL AFTER error_code,
    ADD COLUMN completed_at DATETIME(3) NULL AFTER started_at;

ALTER TABLE problems
    ADD COLUMN ai_origin_workspace_id VARCHAR(64) NULL AFTER ai_generated,
    ADD COLUMN ai_origin_draft_id BIGINT NULL AFTER ai_origin_workspace_id,
    ADD COLUMN ai_review_risk VARCHAR(32) NULL AFTER ai_origin_draft_id,
    ADD COLUMN ai_testcase_verification_mode VARCHAR(32) NULL AFTER ai_review_risk,
    ADD UNIQUE KEY uk_problems_ai_origin_workspace (ai_origin_workspace_id),
    ADD INDEX idx_problems_ai_origin_draft (ai_origin_draft_id);
