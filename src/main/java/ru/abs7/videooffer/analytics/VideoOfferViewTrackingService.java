package ru.abs7.videooffer.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.abs7.videooffer.offer.VideoOffer;
import ru.abs7.videooffer.offer.VideoOfferRepository;
import ru.abs7.videooffer.offer.VideoOfferStatus;
import ru.abs7.videooffer.offer.ViewNotificationGoal;
import ru.abs7.videooffer.offer.ViewNotificationStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.NoSuchElementException;

@Service
public class VideoOfferViewTrackingService {
    private static final Logger log = LoggerFactory.getLogger(VideoOfferViewTrackingService.class);

    private final VideoOfferRepository offerRepository;
    private final VideoOfferViewSessionRepository sessionRepository;
    private final VideoViewGoalEvaluator evaluator;
    private final VideoOfferViewNotificationService notificationService;
    private final TransactionTemplate transactionTemplate;

    public VideoOfferViewTrackingService(
            VideoOfferRepository offerRepository,
            VideoOfferViewSessionRepository sessionRepository,
            VideoViewGoalEvaluator evaluator,
            VideoOfferViewNotificationService notificationService,
            TransactionTemplate transactionTemplate) {
        this.offerRepository = offerRepository;
        this.sessionRepository = sessionRepository;
        this.evaluator = evaluator;
        this.notificationService = notificationService;
        this.transactionTemplate = transactionTemplate;
    }

    public VideoViewProgressResponse track(String token, TrackVideoProgressRequest request) {
        TrackingResult result = transactionTemplate.execute(status -> updateState(token, request));
        if (result == null) {
            throw new IllegalStateException("Не удалось сохранить прогресс просмотра");
        }

        if (result.notificationRequired()) {
            notificationService.deliver(result.offerId());
        }

        return new VideoViewProgressResponse(
                result.trackingActive(),
                result.goalReached(),
                result.notificationStatus());
    }

    private TrackingResult updateState(String token, TrackVideoProgressRequest request) {
        VideoOffer offer = offerRepository.findByPublicTokenForUpdate(token)
                .orElseThrow(() -> new NoSuchElementException("Видеооффер не найден"));

        if (offer.getStatus() != VideoOfferStatus.READY) {
            log.debug("Video progress ignored because offer is not ready: offerId={}, status={}",
                    offer.getId(), offer.getStatus());
            return TrackingResult.inactive(offer);
        }
        if (offer.getExpiresAt() != null && offer.getExpiresAt().isBefore(OffsetDateTime.now())) {
            log.debug("Video progress ignored because offer expired: offerId={}, expiresAt={}",
                    offer.getId(), offer.getExpiresAt());
            return TrackingResult.inactive(offer);
        }
        if (offer.getViewNotificationGoal() == ViewNotificationGoal.NONE
                || offer.getViewNotificationStatus() == ViewNotificationStatus.NOT_REQUIRED) {
            return TrackingResult.inactive(offer);
        }

        VideoOfferViewSession session = sessionRepository
                .findByOffer_IdAndSessionId(offer.getId(), request.sessionId())
                .orElseGet(() -> {
                    log.info("New public video viewing session started: offerId={}, sessionIdPrefix={}, goal={}",
                            offer.getId(), prefix(request.sessionId()), offer.getViewNotificationGoal());
                    return VideoOfferViewSession.create(offer, request.sessionId());
                });

        session.update(
                scale(request.positionSeconds()),
                scale(request.durationSeconds()),
                scale(request.watchedSeconds()));

        boolean reachedInThisUpdate = false;
        if (offer.getViewGoalReachedAt() == null
                && evaluator.reached(offer.getViewNotificationGoal(), session, request.eventType())) {
            session.markGoalReached();
            reachedInThisUpdate = offer.markViewGoalReached(
                    request.sessionId(),
                    session.getMaxPositionSeconds(),
                    session.getDurationSeconds());
            if (reachedInThisUpdate) {
                log.info("Video view goal reached for the first time: offerId={}, entityType={}, entityId={}, "
                                + "goal={}, sessionIdPrefix={}, positionSeconds={}, watchedSeconds={}, durationSeconds={}",
                        offer.getId(),
                        offer.getCrmEntityType(),
                        offer.getCrmEntityId(),
                        offer.getViewNotificationGoal(),
                        prefix(request.sessionId()),
                        session.getMaxPositionSeconds(),
                        session.getWatchedSeconds(),
                        session.getDurationSeconds());
            }
        }

        sessionRepository.save(session);
        offerRepository.save(offer);

        boolean retryNotification = offer.getViewGoalReachedAt() != null
                && (offer.getViewNotificationStatus() == ViewNotificationStatus.PENDING
                || offer.getViewNotificationStatus() == ViewNotificationStatus.ERROR);

        log.debug("Video progress stored: offerId={}, sessionIdPrefix={}, eventType={}, positionSeconds={}, "
                        + "watchedSeconds={}, durationSeconds={}, goalReached={}, notificationStatus={}",
                offer.getId(),
                prefix(request.sessionId()),
                request.eventType(),
                session.getMaxPositionSeconds(),
                session.getWatchedSeconds(),
                session.getDurationSeconds(),
                offer.getViewGoalReachedAt() != null,
                offer.getViewNotificationStatus());

        return new TrackingResult(
                offer.getId(),
                offer.getViewGoalReachedAt() == null,
                offer.getViewGoalReachedAt() != null,
                reachedInThisUpdate || retryNotification,
                offer.getViewNotificationStatus());
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(3, java.math.RoundingMode.HALF_UP);
    }

    private String prefix(String value) {
        return value.length() <= 8 ? value : value.substring(0, 8) + "...";
    }

    private record TrackingResult(
            java.util.UUID offerId,
            boolean trackingActive,
            boolean goalReached,
            boolean notificationRequired,
            ViewNotificationStatus notificationStatus) {
        static TrackingResult inactive(VideoOffer offer) {
            return new TrackingResult(
                    offer.getId(),
                    false,
                    offer.getViewGoalReachedAt() != null,
                    false,
                    offer.getViewNotificationStatus());
        }
    }
}
