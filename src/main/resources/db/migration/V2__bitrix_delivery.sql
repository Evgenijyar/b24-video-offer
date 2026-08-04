ALTER TABLE video_offer
    ADD COLUMN bitrix_delivery_status VARCHAR(30),
    ADD COLUMN bitrix_timeline_comment_id BIGINT,
    ADD COLUMN bitrix_delivery_error TEXT,
    ADD COLUMN bitrix_delivered_at TIMESTAMPTZ;

UPDATE video_offer
SET bitrix_delivery_status = CASE
    WHEN bitrix_member_id IS NULL OR bitrix_member_id = '' THEN 'NOT_REQUIRED'
    ELSE 'PENDING'
END
WHERE bitrix_delivery_status IS NULL;

ALTER TABLE video_offer
    ALTER COLUMN bitrix_delivery_status SET NOT NULL;

CREATE INDEX idx_video_offer_bitrix_delivery
    ON video_offer(bitrix_delivery_status);
