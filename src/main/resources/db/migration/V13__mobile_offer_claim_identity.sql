-- A mobile upload reserves a stable VideoOffer UUID before the VideoOffer row is
-- persisted. The old video_offer_id column is a real FK and therefore cannot hold
-- that future UUID. Keep the reservation identity separate from the persisted link.
ALTER TABLE mobile_video_upload
    ADD COLUMN offer_claim_id UUID;

-- Preserve a possible in-flight claim from an older build where the referenced
-- VideoOffer row already exists (for example a crash between READY persistence
-- and final mobile-upload finalization).
UPDATE mobile_video_upload
SET offer_claim_id = video_offer_id,
    video_offer_id = NULL
WHERE status = 'CONSUMING'
  AND video_offer_id IS NOT NULL;

CREATE UNIQUE INDEX idx_mobile_video_upload_offer_claim
    ON mobile_video_upload(offer_claim_id)
    WHERE offer_claim_id IS NOT NULL;
