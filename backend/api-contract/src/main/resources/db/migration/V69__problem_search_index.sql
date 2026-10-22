-- Semantic search over public problems: one row per problem. The vector is
-- little-endian float32 bytes from the embedding model named next to it. A row
-- that failed waits until next_attempt_at, with backoff, for at most a fixed
-- number of attempts; a content change starts it over.
CREATE TABLE problem_search_index (
    problem_id BIGINT NOT NULL PRIMARY KEY,
    search_text MEDIUMTEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    embedding_model VARCHAR(120) NULL,
    embedding_dimension INT NULL,
    embedding MEDIUMBLOB NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NULL,
    last_error VARCHAR(255) NULL,
    indexed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    INDEX idx_problem_search_index_due (status, next_attempt_at)
);
