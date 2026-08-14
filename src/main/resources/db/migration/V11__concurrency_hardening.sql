ALTER TABLE video_offer
    ADD COLUMN storage_reserved_bytes BIGINT NOT NULL DEFAULT 0;

ALTER TABLE mobile_video_upload
    ADD COLUMN tenant_id BIGINT REFERENCES video_offer_tenant(id) ON DELETE SET NULL,
    ADD COLUMN bitrix_user_id BIGINT,
    ADD COLUMN storage_reserved_bytes BIGINT NOT NULL DEFAULT 0;

CREATE INDEX idx_mobile_video_upload_tenant
    ON mobile_video_upload(tenant_id, status, updated_at);

CREATE INDEX idx_video_offer_storage_reservation
    ON video_offer(tenant_id, storage_reserved_bytes)
    WHERE storage_reserved_bytes > 0;
