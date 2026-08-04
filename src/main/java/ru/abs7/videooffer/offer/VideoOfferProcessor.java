package ru.abs7.videooffer.offer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.abs7.videooffer.bitrix.BitrixTimelineService;
import ru.abs7.videooffer.kontur.KonturVideoDownloader;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class VideoOfferProcessor {
    private static final Logger log = LoggerFactory.getLogger(VideoOfferProcessor.class);

    private final VideoOfferRepository repository;
    private final KonturVideoDownloader downloader;
    private final BitrixTimelineService bitrixTimelineService;

    public VideoOfferProcessor(
            VideoOfferRepository repository,
            KonturVideoDownloader downloader,
            BitrixTimelineService bitrixTimelineService) {
        this.repository = repository;
        this.downloader = downloader;
        this.bitrixTimelineService = bitrixTimelineService;
    }

    @Async
    public void process(UUID id) {
        VideoOffer offer = repository.findById(id).orElse(null);
        if (offer == null) {
            log.warn("Не удалось начать обработку: видеооффер {} не найден", id);
            return;
        }

        Object progressLock = new Object();
        AtomicInteger lastSavedProgress = new AtomicInteger(-1);

        try {
            updateProgress(id, 1);
            KonturVideoDownloader.DownloadResult result = downloader.download(
                    offer.getRecordingKey(),
                    id.toString(),
                    progress -> {
                        synchronized (progressLock) {
                            int previous = lastSavedProgress.get();
                            if (progress >= 99 || progress >= previous + 2) {
                                lastSavedProgress.set(progress);
                                updateProgress(id, progress);
                            }
                        }
                    });

            VideoOffer current = repository.findById(id).orElseThrow();
            current.markReady(result.path().toString(), result.size(), result.quality());
            repository.saveAndFlush(current);

            bitrixTimelineService.publishReadyLink(current);
            repository.saveAndFlush(current);

            log.info("Видеооффер {} подготовлен, publicToken={}, bitrixDelivery={}",
                    id, current.getPublicToken(), current.getBitrixDeliveryStatus());
        } catch (Exception error) {
            String message = rootMessage(error);
            repository.findById(id).ifPresent(current -> {
                current.markError(message);
                repository.saveAndFlush(current);
            });
            log.error("Ошибка подготовки видеооффера {}: {}", id, message, error);
        }
    }

    private void updateProgress(UUID id, int progress) {
        repository.findById(id).ifPresent(current -> {
            current.markPreparing(progress);
            repository.saveAndFlush(current);
        });
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? error.getClass().getSimpleName() : current.getMessage();
    }
}
