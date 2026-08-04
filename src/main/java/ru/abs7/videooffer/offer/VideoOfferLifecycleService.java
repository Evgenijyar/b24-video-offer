package ru.abs7.videooffer.offer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.abs7.videooffer.bitrix.BitrixReadyLinkDeliveryService;

import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class VideoOfferLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(VideoOfferLifecycleService.class);

    private final VideoOfferService service;
    private final VideoOfferProcessor processor;
    private final BitrixReadyLinkDeliveryService bitrixReadyLinkDeliveryService;

    public VideoOfferLifecycleService(
            VideoOfferService service,
            VideoOfferProcessor processor,
            BitrixReadyLinkDeliveryService bitrixReadyLinkDeliveryService) {
        this.service = service;
        this.processor = processor;
        this.bitrixReadyLinkDeliveryService = bitrixReadyLinkDeliveryService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedOffers() {
        log.info("Checking interrupted video offers after application startup");
        var pending = service.findPendingForRecovery();
        if (!pending.isEmpty()) {
            log.info("Возобновляем {} незавершённых видеоофферов", pending.size());
        }
        pending.forEach(offer -> processor.process(offer.getId()));
    }

    @Scheduled(
            initialDelayString = "${app.bitrix.delivery-retry-initial-delay-ms:15000}",
            fixedDelayString = "${app.bitrix.delivery-retry-delay-ms:60000}")
    public void retryBitrixReadyLinks() {
        try {
            int processed = bitrixReadyLinkDeliveryService.retryPendingDeliveries();
            if (processed > 0) {
                log.info("Bitrix ready-link retry cycle completed: processed={}", processed);
            }
        } catch (Exception error) {
            log.error("Bitrix ready-link retry cycle failed: {}", error.getMessage(), error);
        }
    }

    @Scheduled(cron = "${app.video.cleanup-cron:0 15 * * * *}")
    public void cleanupExpiredOffers() {
        log.info("Scheduled expired video offer cleanup started");
        for (VideoOffer offer : service.findExpired()) {
            try {
                if (offer.getVideoFilePath() != null) {
                    Files.deleteIfExists(Path.of(offer.getVideoFilePath()));
                }
                service.delete(offer);
                log.info("Удалён просроченный видеооффер {}", offer.getId());
            } catch (Exception error) {
                log.error("Не удалось удалить просроченный видеооффер {}: {}",
                        offer.getId(), error.getMessage(), error);
            }
        }
        log.info("Scheduled expired video offer cleanup completed");
    }
}
