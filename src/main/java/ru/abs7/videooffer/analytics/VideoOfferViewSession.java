package ru.abs7.videooffer.analytics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import ru.abs7.videooffer.offer.VideoOffer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "video_offer_view_session",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_video_offer_view_session",
                columnNames = {"video_offer_id", "session_id"}))
public class VideoOfferViewSession {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_offer_id", nullable = false)
    private VideoOffer offer;

    @Column(name = "session_id", nullable = false, length = 100)
    private String sessionId;

    @Column(name = "max_position_seconds", nullable = false, precision = 12, scale = 3)
    private BigDecimal maxPositionSeconds;

    @Column(name = "watched_seconds", nullable = false, precision = 12, scale = 3)
    private BigDecimal watchedSeconds;

    @Column(name = "duration_seconds", precision = 12, scale = 3)
    private BigDecimal durationSeconds;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @Column(name = "goal_reached_at")
    private OffsetDateTime goalReachedAt;

    protected VideoOfferViewSession() {
    }

    public static VideoOfferViewSession create(VideoOffer offer, String sessionId) {
        VideoOfferViewSession session = new VideoOfferViewSession();
        session.id = UUID.randomUUID();
        session.offer = offer;
        session.sessionId = sessionId;
        session.maxPositionSeconds = BigDecimal.ZERO;
        session.watchedSeconds = BigDecimal.ZERO;
        session.startedAt = OffsetDateTime.now();
        session.lastSeenAt = session.startedAt;
        return session;
    }

    public void update(BigDecimal position, BigDecimal duration, BigDecimal watched) {
        maxPositionSeconds = max(maxPositionSeconds, nonNegative(position));
        watchedSeconds = max(watchedSeconds, nonNegative(watched));
        if (duration != null && duration.signum() > 0) {
            durationSeconds = max(durationSeconds, duration);
        }
        lastSeenAt = OffsetDateTime.now();
    }

    public void markGoalReached() {
        if (goalReachedAt == null) {
            goalReachedAt = OffsetDateTime.now();
        }
        lastSeenAt = OffsetDateTime.now();
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return value;
    }

    private static BigDecimal max(BigDecimal left, BigDecimal right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.max(right);
    }

    public UUID getId() { return id; }
    public VideoOffer getOffer() { return offer; }
    public String getSessionId() { return sessionId; }
    public BigDecimal getMaxPositionSeconds() { return maxPositionSeconds; }
    public BigDecimal getWatchedSeconds() { return watchedSeconds; }
    public BigDecimal getDurationSeconds() { return durationSeconds; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public OffsetDateTime getGoalReachedAt() { return goalReachedAt; }
}
