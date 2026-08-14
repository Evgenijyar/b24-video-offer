package ru.abs7.videooffer.offer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.abs7.videooffer.bitrix.BitrixReadyLinkDeliveryService;
import ru.abs7.videooffer.concurrency.TenantFairVideoScheduler;
import ru.abs7.videooffer.source.UniversalVideoDownloader;
import ru.abs7.videooffer.tenant.TenantAccessService;

import java.nio.file.Files;

import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class VideoOfferProcessor {
    private static final Logger log = LoggerFactory.getLogger(VideoOfferProcessor.class);

    private final VideoOfferRepository repository;
    private final UniversalVideoDownloader downloader;
    private final BitrixReadyLinkDeliveryService bitrixReadyLinkDeliveryService;
    private final TenantAccessService accessService;
    private final TenantFairVideoScheduler scheduler;

    public VideoOfferProcessor(
            VideoOfferRepository repository,
            UniversalVideoDownloader downloader,
            BitrixReadyLinkDeliveryService bitrixReadyLinkDeliveryService,
            TenantAccessService accessService,
            TenantFairVideoScheduler scheduler) {
        this.repository = repository;
        this.downloader = downloader;
        this.bitrixReadyLinkDeliveryService = bitrixReadyLinkDeliveryService;
        this.accessService = accessService;
        this.scheduler = scheduler;
    }

    public void process(UUID id) {
        VideoOffer snapshot = repository.findById(id).orElse(null);
        if (snapshot == null) {
            log.warn("Video offer processing cannot be queued because entity was not found: offerId={}", id);
            return;
        }
        try {
            scheduler.submit(snapshot.getTenantId(), "offer:" + id, () -> processNow(id));
        } catch (RejectedExecutionException rejected) {
            String message = "Очередь обработки видео временно переполнена. Повторите попытку позже";
            log.error("Video offer processing queue rejected task: offerId={}, tenantId={}",
                    id, snapshot.getTenantId(), rejected);
            repository.findById(id).ifPresent(current -> {
                current.markError(message);
                repository.saveAndFlush(current);
                accessService.releaseConsumedOffer(current.getTenantId(), current.getBitrixUserId());
            });
        }
    }

    private void processNow(UUID id) {
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
                    id, result.sourceType(), result.path(), result.size(), result.quality(), elapsedMillis(startedAt));

            try {
                accessService.reserveStorageForOffer(offer.getTenantId(), id, result.size());
            } catch (RuntimeException quotaError) {
                try { Files.deleteIfExists(result.path()); } catch (Exception cleanupError) {
                    log.warn("Cannot delete downloaded video rejected by tenant quota: offerId={}, path={}, error={}",
                            id, result.path(), cleanupError.getMessage());
                }
                throw quotaError;
            }

            VideoOffer current = repository.findById(id).orElseThrow(() ->
                    new IllegalStateException("Видеооффер исчез из базы после скачивания: " + id));
            log.info("Marking video offer ready: offerId={}, previousStatus={}, path={}, bytes={}, quality={}",
                    id, current.getStatus(), result.path(), result.size(), result.quality());
            accessService.markDownloadedOfferReady(id, result.path().toString(), result.size(), result.quality());
            current = repository.findById(id).orElseThrow(() ->
                    new IllegalStateException("Видеооффер исчез из базы после фиксации готового файла: " + id));
            log.info("Video offer READY state persisted: offerId={}, publicToken={}, readyAt={}",
                    id, current.getPublicToken(), current.getReadyAt());

            log.info("Submitting ready link delivery to fast system executor: offerId={}, bitrixMemberId={}, entityType={}, entityId={}",
                    id,
                    current.getBitrixMemberId(),
                    current.getCrmEntityType(),
                    current.getCrmEntityId());
            bitrixReadyLinkDeliveryService.deliverAsync(id);

            log.info("Video offer processing completed: offerId={}, publicToken={}, status={}, bitrixDelivery={}, "
                            + "totalDurationMs={}",
                    id,
                    current.getPublicToken(),
                    current.getStatus(),
                    current.getBitrixDeliveryStatus(),
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
                accessService.releaseConsumedOffer(current.getTenantId(), current.getBitrixUserId());
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
