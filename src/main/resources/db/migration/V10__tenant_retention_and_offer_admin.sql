ALTER TABLE video_offer_tenant
    ADD COLUMN retention_days INTEGER NOT NULL DEFAULT 7;

UPDATE video_offer_tenant
SET retention_days = 7
WHERE retention_days IS NULL OR retention_days < 1;
