package ru.abs7.videooffer.analytics;

import org.springframework.stereotype.Component;
import ru.abs7.videooffer.offer.ViewNotificationGoal;

import java.math.BigDecimal;

@Component
public class VideoViewGoalEvaluator {
    private static final BigDecimal ONE_MINUTE_SECONDS = BigDecimal.valueOf(60);

    public boolean reached(
            ViewNotificationGoal goal,
            VideoOfferViewSession session,
            VideoPlaybackProgressEventType eventType) {
        if (goal == null || goal == ViewNotificationGoal.NONE) {
            return false;
        }

        BigDecimal position = zeroIfNull(session.getMaxPositionSeconds());
        BigDecimal watched = zeroIfNull(session.getWatchedSeconds());
        BigDecimal duration = session.getDurationSeconds();

        return switch (goal) {
            case NONE -> false;
            case ONE_MINUTE -> oneMinuteReached(position, watched, duration, eventType);
            case HALF -> halfReached(position, watched, duration);
            case COMPLETED -> completed(position, watched, duration, eventType);
        };
    }

    private boolean oneMinuteReached(
            BigDecimal position,
            BigDecimal watched,
            BigDecimal duration,
            VideoPlaybackProgressEventType eventType) {
        if (duration != null && duration.signum() > 0
                && duration.compareTo(ONE_MINUTE_SECONDS) < 0) {
            return completed(position, watched, duration, eventType);
        }
        return position.compareTo(BigDecimal.valueOf(59)) >= 0
                && watched.compareTo(BigDecimal.valueOf(59)) >= 0;
    }

    private boolean halfReached(
            BigDecimal position,
            BigDecimal watched,
            BigDecimal duration) {
        if (duration == null || duration.signum() <= 0) {
            return false;
        }
        BigDecimal half = duration.multiply(BigDecimal.valueOf(0.5));
        BigDecimal minimumPosition = half.multiply(BigDecimal.valueOf(0.98));
        BigDecimal minimumWatched = half.multiply(BigDecimal.valueOf(0.95));
        return position.compareTo(minimumPosition) >= 0
                && watched.compareTo(minimumWatched) >= 0;
    }

    private boolean completed(
            BigDecimal position,
            BigDecimal watched,
            BigDecimal duration,
            VideoPlaybackProgressEventType eventType) {
        if (duration == null || duration.signum() <= 0) {
            return eventType == VideoPlaybackProgressEventType.ENDED;
        }
        BigDecimal minimumPosition = duration.multiply(BigDecimal.valueOf(0.98));
        BigDecimal minimumWatched = duration.multiply(BigDecimal.valueOf(0.95));
        boolean nearEnd = position.compareTo(minimumPosition) >= 0;
        boolean enoughWatched = watched.compareTo(minimumWatched) >= 0;
        return enoughWatched && (eventType == VideoPlaybackProgressEventType.ENDED || nearEnd);
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
