ALTER TABLE mobile_video_upload
    ADD COLUMN source_kind VARCHAR(20) NOT NULL DEFAULT 'RECORDING',
    ADD COLUMN declared_size_bytes BIGINT;

ALTER TABLE mobile_video_upload
    ALTER COLUMN source_kind DROP DEFAULT;
