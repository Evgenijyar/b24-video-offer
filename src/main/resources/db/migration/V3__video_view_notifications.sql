ALTER TABLE video_offer
    ADD COLUMN view_notification_goal VARCHAR(30),
    ADD COLUMN view_notification_status VARCHAR(30),
    ADD COLUMN view_goal_reached_at TIMESTAMPTZ,
    ADD COLUMN view_goal_session_id VARCHAR(100),
    ADD COLUMN view_goal_position_seconds NUMERIC(12,3),
    ADD COLUMN view_goal_duration_seconds NUMERIC(12,3),
    ADD COLUMN view_notification_comment_id BIGINT,
    ADD COLUMN view_notification_error TEXT,
    ADD COLUMN view_notification_sent_at TIMESTAMPTZ;

UPDATE video_offer
SET view_notification_goal = 'NONE'
WHERE view_notification_goal IS NULL;

UPDATE video_offer
SET view_notification_status = 'NOT_REQUIRED'
WHERE view_notification_status IS NULL;

ALTER TABLE video_offer
    ALTER COLUMN view_notification_goal SET NOT NULL,
    ALTER COLUMN view_notification_status SET NOT NULL;

CREATE INDEX idx_video_offer_view_notification
    ON video_offer(view_notification_status, view_goal_reached_at);

CREATE TABLE video_offer_view_session (
    id UUID PRIMARY KEY,
    video_offer_id UUID NOT NULL REFERENCES video_offer(id) ON DELETE CASCADE,
    session_id VARCHAR(100) NOT NULL,
    max_position_seconds NUMERIC(12,3) NOT NULL DEFAULT 0,
    watched_seconds NUMERIC(12,3) NOT NULL DEFAULT 0,
    duration_seconds NUMERIC(12,3),
    started_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    goal_reached_at TIMESTAMPTZ,
    CONSTRAINT uk_video_offer_view_session UNIQUE(video_offer_id, session_id)
);

CREATE INDEX idx_video_offer_view_session_offer
    ON video_offer_view_session(video_offer_id, last_seen_at);
