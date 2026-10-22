-- Judge diagnostics: which stage produced a verdict and the raw sandbox status,
-- plus the package testcase count so a submission that stopped at its first
-- failing case can still show how many cases there were.
ALTER TABLE submissions
    ADD COLUMN judge_phase VARCHAR(16) NULL AFTER status,
    ADD COLUMN sandbox_status VARCHAR(64) NULL AFTER judge_phase,
    ADD COLUMN case_total INT NULL AFTER max_score;

ALTER TABLE submission_case_results
    ADD COLUMN judge_phase VARCHAR(16) NULL AFTER status,
    ADD COLUMN sandbox_status VARCHAR(64) NULL AFTER judge_phase,
    ADD COLUMN sample TINYINT NULL AFTER sandbox_status;

ALTER TABLE judge_audit_logs
    ADD COLUMN judge_phase VARCHAR(16) NULL AFTER to_status,
    ADD COLUMN sandbox_status VARCHAR(64) NULL AFTER judge_phase;
