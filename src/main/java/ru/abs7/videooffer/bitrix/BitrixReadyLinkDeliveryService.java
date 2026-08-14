package ru.abs7.videooffer.bitrix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.abs7.videooffer.offer.VideoOffer;
import ru.abs7.videooffer.offer.VideoOfferRepository;
import ru.abs7.videooffer.offer.VideoOfferStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BitrixReadyLinkDeliveryService {
    private static final Logger log = LoggerFactory.getLogger(BitrixReadyLinkDeliveryService.class);
    private static final int RETRY_BATCH_SIZE = 20;
    private static final int STALE_SENDING_MINUTES = 2;

    private final VideoOfferRepository repository;
    private final BitrixTimelineService timelineService;
    private final TransactionTemplate transactionTemplate;
    private final ThreadPoolTaskExecutor systemAsyncExecutor;
    private final Set<UUID> scheduledOfferIds = ConcurrentHashMap.newKeySet();

    public BitrixReadyLinkDeliveryService(
            VideoOfferRepository repository,
            BitrixTimelineService timelineService,
            PlatformTransactionManager transactionManager,
            @Qualifier("systemAsyncExecutor") ThreadPoolTaskExecutor systemAsyncExecutor) {
        this.repository = repository;
        this.timelineService = timelineService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.systemAsyncExecutor = systemAsyncExecutor;
    }

    public void deliverAsync(UUID offerId) {
        if (offerId == null || !scheduledOfferIds.add(offerId)) return;
        try {
            systemAsyncExecutor.execute(() -> {
                try {
                    deliver(offerId);
                } finally {
                    scheduledOfferIds.remove(offerId);
                }
            });
        } catch (RuntimeException rejected) {
            scheduledOfferIds.remove(offerId);
            // Delivery state remains PENDING/ERROR and will be picked up by the
            // durable retry loop. Never block video workers or scheduler threads.
            log.error("Bitrix ready-link executor rejected task: offerId={}, error={}",
                    offerId, rejected.getMessage(), rejected);
        }
    }

    public void deliver(UUID offerId) {
        VideoOffer claimed = claim(offerId);
        if (claimed == null) {
            return;
        }

        log.info("Bitrix ready-link delivery claimed: offerId={}, entityType={}, entityId={}",
                claimed.getId(), claimed.getCrmEntityType(), claimed.getCrmEntityId());
        timelineService.publishReadyLink(claimed);
        repository.saveAndFlush(claimed);
        log.info("Bitrix ready-link delivery finished: offerId={}, status={}, commentId={}, error={}",
                claimed.getId(),
                claimed.getBitrixDeliveryStatus(),
                claimed.getBitrixTimelineCommentId(),
                claimed.getBitrixDeliveryError());
    }

    public int retryPendingDeliveries() {
        releaseStaleSendingDeliveries();
        List<VideoOffer> pending = repository.findBitrixDeliveriesForRetry(
                VideoOfferStatus.READY,
                List.of("PENDING", "ERROR"),
                OffsetDateTime.now(),
                PageRequest.of(0, RETRY_BATCH_SIZE));

        if (!pending.isEmpty()) {
            log.info("Retrying Bitrix ready-link deliveries: count={}", pending.size());
        }
        for (VideoOffer offer : pending) {
            deliverAsync(offer.getId());
        }
        return pending.size();
    }

    private VideoOffer claim(UUID offerId) {
        return transactionTemplate.execute(status -> {
            VideoOffer offer = repository.findByIdForUpdate(offerId).orElse(null);
            if (offer == null) {
                log.warn("Bitrix ready-link delivery skipped because offer was not found: offerId={}", offerId);
                return null;
            }
            if (offer.getBitrixMemberId() == null || offer.getBitrixMemberId().isBlank()) {
                offer.markBitrixDeliveryNotRequired();
                repository.saveAndFlush(offer);
                return null;
            }
            if (!offer.claimBitrixDelivery()) {
                log.debug("Bitrix ready-link delivery was not claimable: offerId={}, offerStatus={}, deliveryStatus={}",
                        offerId, offer.getStatus(), offer.getBitrixDeliveryStatus());
                return null;
            }
            return repository.saveAndFlush(offer);
        });
    }

    private void releaseStaleSendingDeliveries() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime staleBefore = now.minusMinutes(STALE_SENDING_MINUTES);
        List<VideoOffer> stale = repository.findStaleBitrixDeliveries(
                VideoOfferStatus.READY,
                "SENDING",
                staleBefore,
                now,
                PageRequest.of(0, RETRY_BATCH_SIZE));

        for (VideoOffer candidate : stale) {
            transactionTemplate.executeWithoutResult(status -> repository.findByIdForUpdate(candidate.getId())
                    .ifPresent(offer -> {
                        if ("SENDING".equals(offer.getBitrixDeliveryStatus())
                                && offer.getUpdatedAt().isBefore(staleBefore)) {
                            offer.releaseStaleBitrixDelivery(
                                    "Предыдущая отправка ссылки зависла или была прервана; выполняется повторная попытка");
                            repository.saveAndFlush(offer);
                            log.warn("Released stale Bitrix ready-link delivery: offerId={}", offer.getId());
                        }
                    }));
        }
    }
}
