CREATE TABLE video_offer_tenant (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    portal_domain VARCHAR(255) NOT NULL UNIQUE,
    member_id VARCHAR(100) UNIQUE,
    webhook_url TEXT,
    local_client_id VARCHAR(255),
    local_client_secret TEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    package_name VARCHAR(120) NOT NULL DEFAULT 'Beta',
    seat_limit INTEGER NOT NULL DEFAULT 3,
    offer_limit INTEGER NOT NULL DEFAULT 50,
    offers_used BIGINT NOT NULL DEFAULT 0,
    disk_quota_bytes BIGINT NOT NULL DEFAULT 10737418240,
    allow_any_entity BOOLEAN NOT NULL DEFAULT FALSE,
    primary_admin_user_id BIGINT,
    page_settings_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE video_offer_tenant_user (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES video_offer_tenant(id) ON DELETE CASCADE,
    bitrix_user_id BIGINT NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    offer_access BOOLEAN NOT NULL DEFAULT FALSE,
    admin BOOLEAN NOT NULL DEFAULT FALSE,
    primary_admin BOOLEAN NOT NULL DEFAULT FALSE,
    default_accompanying_text TEXT,
    default_client_message TEXT,
    offers_used BIGINT NOT NULL DEFAULT 0,
    last_seen_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_video_offer_tenant_user UNIQUE (tenant_id, bitrix_user_id)
);

CREATE INDEX idx_video_offer_tenant_user_access
    ON video_offer_tenant_user(tenant_id, offer_access, active);

ALTER TABLE video_offer
    ADD COLUMN tenant_id BIGINT REFERENCES video_offer_tenant(id) ON DELETE SET NULL;

CREATE INDEX idx_video_offer_tenant ON video_offer(tenant_id, created_at);
