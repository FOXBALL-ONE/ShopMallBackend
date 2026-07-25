CREATE TABLE IF NOT EXISTS file_metadata (
    id UUID PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    stored_filename VARCHAR(255) NOT NULL UNIQUE,
    relative_path VARCHAR(512) NOT NULL UNIQUE,
    content_type VARCHAR(255),
    byte_size BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    storage VARCHAR(16) NOT NULL DEFAULT 'local',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE file_metadata
    ADD COLUMN IF NOT EXISTS storage VARCHAR(16) NOT NULL DEFAULT 'local';

CREATE INDEX IF NOT EXISTS idx_file_metadata_owner_created
    ON file_metadata (owner_id, created_at DESC);
