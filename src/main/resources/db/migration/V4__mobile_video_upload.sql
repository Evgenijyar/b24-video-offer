CREATE TABLE mobile_video_upload (
    id UUID PRIMARY KEY,
    upload_token VARCHAR(100) NOT NULL UNIQUE,
    bitrix_member_id VARCHAR(100) NOT NULL,
    crm_entity_type VARCHAR(20) NOT NULL,
    crm_entity_id BIGINT NOT NULL,
    mime_type VARCHAR(160) NOT NULL,
    source_file_path TEXT NOT NULL,
    normalized_file_path TEXT,
    bytes_received BIGINT NOT NULL DEFAULT 0,
    next_sequence INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL,
    error_message TEXT,
    video_offer_id UUID REFERENCES video_offer(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    ready_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_mobile_video_upload_expires
    ON mobile_video_upload(expires_at);

CREATE INDEX idx_mobile_video_upload_status
    ON mobile_video_upload(status, updated_at);
