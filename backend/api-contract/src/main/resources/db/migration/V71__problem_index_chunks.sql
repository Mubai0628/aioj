-- Public problem search index.
-- One current index row is kept for each problem.
-- Embeddings are nullable because the first version supports keyword fallback.

CREATE TABLE problem_index_chunks (
    id BIGINT PRIMARY KEY,
    problem_id BIGINT NOT NULL,
    search_text MEDIUMTEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    embedding_model VARCHAR(120) NULL,
    embedding_dimension INT NULL,
    embedding_json MEDIUMTEXT NULL,
    index_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    indexed_at DATETIME(3) NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,

    UNIQUE KEY uk_problem_index_problem (problem_id),
    KEY idx_problem_index_status (index_status, updated_at),
    KEY idx_problem_index_hash (content_hash)
);