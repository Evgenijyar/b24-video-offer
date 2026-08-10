ALTER TABLE video_offer
    ALTER COLUMN video_quality TYPE VARCHAR(120);

ALTER TABLE mobile_video_upload
    ADD COLUMN processing_progress_percent INTEGER NOT NULL DEFAULT 0;
