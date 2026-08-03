package ru.abs7.videooffer.offer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class VideoOfferLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(VideoOfferLifecycleService.class);

    private final VideoOfferService service;
    private final VideoOfferProcessor processor;

    public VideoOfferLifecycleService(VideoOfferService service, VideoOfferProcessor processor) {
        this.service = service;
        this.processor = processor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedOffers() {
        var pending = service.findPendingForRecovery();
        if (!pending.isEmpty()) {
            log.info("Возобновляем {} незавершённых видеоофферов", pending.size());
        }
        pending.forEach(offer -> processor.process(offer.getId()));
    }

    @Scheduled(cron = "${app.video.cleanup-cron:0 15 * * * *}")
    public void cleanupExpiredOffers() {
        for (VideoOffer offer : service.findExpired()) {
            try {
                if (offer.getVideoFilePath() != null) {
                    Files.deleteIfExists(Path.of(offer.getVideoFilePath()));
                }
                service.delete(offer);
                log.info("Удалён просроченный видеооффер {}", offer.getId());
            } catch (Exception error) {
                log.warn("Не удалось удалить просроченный видеооффер {}: {}",
                        offer.getId(), error.getMessage());
            }
        }
    }
}
