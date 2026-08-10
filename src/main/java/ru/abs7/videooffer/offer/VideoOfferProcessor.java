package ru.abs7.videooffer.offer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.abs7.videooffer.bitrix.BitrixReadyLinkDeliveryService;
import ru.abs7.videooffer.source.UniversalVideoDownloader;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class VideoOfferProcessor {
    private static final Logger log = LoggerFactory.getLogger(VideoOfferProcessor.class);

    private final VideoOfferRepository repository;
    private final UniversalVideoDownloader downloader;
    private final BitrixReadyLinkDeliveryService bitrixReadyLinkDeliveryService;

    public VideoOfferProcessor(
            VideoOfferRepository repository,
            UniversalVideoDownloader downloader,
            BitrixReadyLinkDeliveryService bitrixReadyLinkDeliveryService) {
        this.repository = repository;
        this.downloader = downloader;
        this.bitrixReadyLinkDeliveryService = bitrixReadyLinkDeliveryService;
    }

    @Async
    public void process(UUID id) {
        long startedAt = System.nanoTime();
        log.info("Video offer processing started: offerId={}, thread={}",
                id, Thread.currentThread().getName());

        VideoOffer offer = repository.findById(id).orElse(null);
        if (offer == null) {
            log.warn("Video offer processing cannot start because entity was not found: offerId={}", id);
            return;
        }

        log.info("Video offer loaded for processing: offerId={}, entityType={}, entityId={}, recordingKey={}, "
                        + "status={}, currentProgress={}%, bitrixMemberId={}",
                id,
                offer.getCrmEntityType(),
                offer.getCrmEntityId(),
                offer.getRecordingKey(),
                offer.getStatus(),
                offer.getProgressPercent(),
                offer.getBitrixMemberId());

        Object progressLock = new Object();
        AtomicInteger lastSavedProgress = new AtomicInteger(-1);

        try {
            updateProgress(id, 1, "processing-started");
            log.info("Calling universal video downloader: offerId={}, sourceUrlPresent={}, recordingKey={}",
                    id, offer.getSourceRecordingUrl() != null, offer.getRecordingKey());

            UniversalVideoDownloader.DownloadResult result = downloader.download(
                    offer.getSourceRecordingUrl(),
                    offer.getRecordingKey(),
                    id.toString(),
                    progress -> {
                        synchronized (progressLock) {
                            int previous = lastSavedProgress.get();
                            if (progress >= 99 || progress >= previous + 2) {
                                lastSavedProgress.set(progress);
                                updateProgress(id, progress, "source-download");
                            }
                        }
                    });

            log.info("Universal downloader returned successfully: offerId={}, sourceType={}, path={}, bytes={}, quality={}, durationMs={}",
                    id,
                    result.sourceType(),
                    result.path(),
                    result.size(),
                    result.quality(),
                    elapsedMillis(startedAt));

            VideoOffer current = repository.findById(id).orElseThrow(() ->
                    new IllegalStateException("Видеооффер исчез из базы после скачивания: " + id));
            log.info("Marking video offer ready: offerId={}, previousStatus={}, path={}, bytes={}, quality={}",
                    id, current.getStatus(), result.path(), result.size(), result.quality());
            current.markReady(result.path().toString(), result.size(), result.quality());
            repository.saveAndFlush(current);
            log.info("Video offer READY state persisted: offerId={}, publicToken={}, readyAt={}",
                    id, current.getPublicToken(), current.getReadyAt());

            log.info("Publishing ready link to Bitrix24: offerId={}, bitrixMemberId={}, entityType={}, entityId={}",
                    id,
                    current.getBitrixMemberId(),
                    current.getCrmEntityType(),
                    current.getCrmEntityId());
            bitrixReadyLinkDeliveryService.deliver(id);
            VideoOffer delivered = repository.findById(id).orElseThrow(() ->
                    new IllegalStateException("Видеооффер исчез из базы после отправки в Bitrix24: " + id));
            log.info("Bitrix delivery state persisted: offerId={}, deliveryStatus={}, commentId={}, deliveryError={}",
                    id,
                    delivered.getBitrixDeliveryStatus(),
                    delivered.getBitrixTimelineCommentId(),
                    delivered.getBitrixDeliveryError());

            log.info("Video offer processing completed: offerId={}, publicToken={}, status={}, bitrixDelivery={}, "
                            + "totalDurationMs={}",
                    id,
                    delivered.getPublicToken(),
                    delivered.getStatus(),
                    delivered.getBitrixDeliveryStatus(),
                    elapsedMillis(startedAt));
        } catch (Exception error) {
            String message = rootMessage(error);
            log.error("Video offer processing failed: offerId={}, durationMs={}, error={}",
                    id, elapsedMillis(startedAt), message, error);

            repository.findById(id).ifPresentOrElse(current -> {
                log.info("Persisting ERROR state: offerId={}, previousStatus={}, error={}",
                        id, current.getStatus(), message);
                current.markError(message);
                repository.saveAndFlush(current);
                log.info("ERROR state persisted: offerId={}, status={}, errorMessage={}",
                        id, current.getStatus(), current.getErrorMessage());
            }, () -> log.error("Could not persist ERROR state because offer was not found: offerId={}", id));
        }
    }

    private void updateProgress(UUID id, int progress, String stage) {
        repository.findById(id).ifPresentOrElse(current -> {
            int previous = current.getProgressPercent() == null ? 0 : current.getProgressPercent();
            current.markPreparing(progress);
            repository.saveAndFlush(current);
            log.info("Video offer progress persisted: offerId={}, stage={}, previous={}%, current={}%, status={}",
                    id, stage, previous, current.getProgressPercent(), current.getStatus());
        }, () -> log.warn("Progress update skipped because offer was not found: offerId={}, progress={}%, stage={}",
                id, progress, stage));
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

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
