CREATE TABLE bitrix_installation (
    id BIGSERIAL PRIMARY KEY,
    member_id VARCHAR(100) NOT NULL UNIQUE,
    portal_domain VARCHAR(255) NOT NULL,
    access_token TEXT,
    refresh_token TEXT,
    token_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE video_offer (
    id UUID PRIMARY KEY,
    public_token VARCHAR(80) NOT NULL UNIQUE,
    crm_entity_type VARCHAR(20) NOT NULL,
    crm_entity_id BIGINT NOT NULL,
    bitrix_member_id VARCHAR(100),
    bitrix_user_id BIGINT,
    source_recording_url TEXT NOT NULL,
    recording_key VARCHAR(255) NOT NULL,
    accompanying_text TEXT,
    status VARCHAR(30) NOT NULL,
    progress_percent INTEGER NOT NULL DEFAULT 0,
    video_file_path TEXT,
    video_file_size BIGINT,
    video_quality VARCHAR(30),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    ready_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ
);
CREATE INDEX idx_video_offer_crm ON video_offer(crm_entity_type, crm_entity_id);
CREATE INDEX idx_video_offer_status ON video_offer(status);

CREATE TABLE video_offer_event (
    id BIGSERIAL PRIMARY KEY,
    video_offer_id UUID NOT NULL REFERENCES video_offer(id) ON DELETE CASCADE,
    event_type VARCHAR(30) NOT NULL,
    playback_position_seconds NUMERIC(12,3),
    user_agent TEXT,
    ip_hash VARCHAR(128),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_video_offer_event_offer ON video_offer_event(video_offer_id, occurred_at);
