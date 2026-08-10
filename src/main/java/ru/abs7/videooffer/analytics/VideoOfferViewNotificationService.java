package ru.abs7.videooffer.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.abs7.videooffer.bitrix.BitrixTimelineService;
import ru.abs7.videooffer.offer.VideoOffer;
import ru.abs7.videooffer.offer.VideoOfferRepository;
import ru.abs7.videooffer.offer.ViewNotificationStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class VideoOfferViewNotificationService {
    private static final Logger log = LoggerFactory.getLogger(VideoOfferViewNotificationService.class);

    private final VideoOfferRepository repository;
    private final BitrixTimelineService timelineService;
    private final TransactionTemplate transactionTemplate;

    public VideoOfferViewNotificationService(
            VideoOfferRepository repository,
            BitrixTimelineService timelineService,
            TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.timelineService = timelineService;
        this.transactionTemplate = transactionTemplate;
    }

    @Async
    public void deliver(UUID offerId) {
        deliverNow(offerId);
    }

    @Scheduled(
            fixedDelayString = "${app.view-notification.retry-delay-ms:300000}",
            initialDelayString = "${app.view-notification.initial-delay-ms:60000}")
    public void retryPendingNotifications() {
        recoverStaleSendingNotifications();
        List<VideoOffer> pending = repository.findViewNotificationsForRetry(
                List.of(ViewNotificationStatus.PENDING, ViewNotificationStatus.ERROR),
                OffsetDateTime.now(),
                PageRequest.of(0, 50));
        if (!pending.isEmpty()) {
            log.info("Retrying pending Bitrix view notifications: count={}", pending.size());
        }
        pending.forEach(offer -> deliverNow(offer.getId()));
    }

    private void recoverStaleSendingNotifications() {
        OffsetDateTime now = OffsetDateTime.now();
        List<VideoOffer> stale = repository.findStaleViewNotifications(
                ViewNotificationStatus.SENDING,
                now.minusMinutes(2),
                now,
                PageRequest.of(0, 50));
        for (VideoOffer candidate : stale) {
            transactionTemplate.executeWithoutResult(status -> repository.findByIdForUpdate(candidate.getId())
                    .ifPresent(offer -> {
                        if (offer.getViewNotificationStatus() == ViewNotificationStatus.SENDING
                                && offer.getUpdatedAt().isBefore(OffsetDateTime.now().minusMinutes(2))) {
                            offer.releaseStaleViewNotification(
                                    "Повторная отправка после незавершённой предыдущей попытки");
                            repository.saveAndFlush(offer);
                            log.warn("Stale Bitrix view notification released for retry: offerId={}",
                                    offer.getId());
                        }
                    }));
        }
    }

    private void deliverNow(UUID offerId) {
        VideoOffer claimed = transactionTemplate.execute(status -> {
            VideoOffer offer = repository.findByIdForUpdate(offerId).orElse(null);
            if (offer == null || !offer.claimViewNotification()) {
                return null;
            }
            repository.saveAndFlush(offer);
            log.info("Bitrix view notification claimed: offerId={}, entityType={}, entityId={}, goal={}",
                    offer.getId(), offer.getCrmEntityType(), offer.getCrmEntityId(),
                    offer.getViewNotificationGoal());
            return offer;
        });

        if (claimed == null) {
            return;
        }

        try {
            Long activityId = timelineService.createViewGoalTodo(claimed);
            transactionTemplate.executeWithoutResult(status -> repository.findByIdForUpdate(offerId)
                    .ifPresent(offer -> {
                        offer.markViewNotificationDelivered(activityId);
                        repository.saveAndFlush(offer);
                    }));
            log.info("Bitrix view notification delivery completed as CRM todo: offerId={}, activityId={}",
                    offerId, activityId);
        } catch (Exception error) {
            String message = rootMessage(error);
            transactionTemplate.executeWithoutResult(status -> repository.findByIdForUpdate(offerId)
                    .ifPresent(offer -> {
                        offer.markViewNotificationError(message);
                        repository.saveAndFlush(offer);
                    }));
            log.error("Bitrix view notification delivery failed: offerId={}, error={}",
                    offerId, message, error);
        }
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? error.getClass().getSimpleName()
                : current.getMessage();
    }
}
