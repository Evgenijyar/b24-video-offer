package ru.abs7.videooffer.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.abs7.videooffer.bitrix.BitrixTimelineService;
import ru.abs7.videooffer.offer.VideoOffer;
import ru.abs7.videooffer.offer.VideoOfferRepository;
import ru.abs7.videooffer.offer.ViewNotificationStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class VideoOfferViewNotificationService {
    private static final Logger log = LoggerFactory.getLogger(VideoOfferViewNotificationService.class);

    private final VideoOfferRepository repository;
    private final BitrixTimelineService timelineService;
    private final TransactionTemplate transactionTemplate;
    private final ThreadPoolTaskExecutor notificationExecutor;
    private final Set<UUID> scheduledOfferIds = ConcurrentHashMap.newKeySet();

    public VideoOfferViewNotificationService(
            VideoOfferRepository repository,
            BitrixTimelineService timelineService,
            TransactionTemplate transactionTemplate,
            @Qualifier("notificationExecutor") ThreadPoolTaskExecutor notificationExecutor) {
        this.repository = repository;
        this.timelineService = timelineService;
        this.transactionTemplate = transactionTemplate;
        this.notificationExecutor = notificationExecutor;
    }

    public void deliver(UUID offerId) {
        if (offerId == null || !scheduledOfferIds.add(offerId)) return;
        try {
            notificationExecutor.execute(() -> {
                try {
                    deliverNow(offerId);
                } finally {
                    scheduledOfferIds.remove(offerId);
                }
            });
        } catch (RuntimeException rejected) {
            scheduledOfferIds.remove(offerId);
            // The durable notification state is still PENDING/ERROR, so the scheduled
            // retry loop can pick it up later without losing the event.
            log.error("Bitrix view notification executor rejected task: offerId={}, error={}",
                    offerId, rejected.getMessage(), rejected);
        }
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
        pending.forEach(offer -> deliver(offer.getId()));
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
            Long activityId = claimed.getViewNotificationActivityId();
            Long responsibleId = claimed.getViewNotificationResponsibleId();

            if (activityId == null || responsibleId == null || responsibleId <= 0) {
                BitrixTimelineService.ViewGoalTodo todo = timelineService.createViewGoalTodo(claimed);
                activityId = todo.activityId();
                responsibleId = todo.responsibleId();
                Long persistedActivityId = activityId;
                Long persistedResponsibleId = responsibleId;
                transactionTemplate.executeWithoutResult(status -> repository.findByIdForUpdate(offerId)
                        .ifPresent(offer -> {
                            offer.markViewNotificationActivityCreated(persistedActivityId, persistedResponsibleId);
                            repository.saveAndFlush(offer);
                        }));
                log.info("Bitrix view-goal CRM todo persisted before bell notification: offerId={}, activityId={}, responsibleId={}",
                        offerId, activityId, responsibleId);
            } else {
                log.info("Reusing existing Bitrix view-goal CRM todo before bell retry: offerId={}, activityId={}, responsibleId={}",
                        offerId, activityId, responsibleId);
            }

            Long notificationId = timelineService.sendViewGoalSystemNotification(
                    claimed, responsibleId, activityId);
            Long finalActivityId = activityId;
            Long finalResponsibleId = responsibleId;
            transactionTemplate.executeWithoutResult(status -> repository.findByIdForUpdate(offerId)
                    .ifPresent(offer -> {
                        offer.markViewNotificationDelivered(finalActivityId, finalResponsibleId, notificationId);
                        repository.saveAndFlush(offer);
                    }));
            log.info("Bitrix view notification delivery completed: offerId={}, activityId={}, responsibleId={}, notificationId={}",
                    offerId, activityId, responsibleId, notificationId);
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
