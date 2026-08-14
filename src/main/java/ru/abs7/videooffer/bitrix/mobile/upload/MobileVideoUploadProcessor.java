package ru.abs7.videooffer.bitrix.mobile.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import ru.abs7.videooffer.concurrency.TenantFairVideoScheduler;

@Service
public class MobileVideoUploadProcessor {
    private static final Logger log = LoggerFactory.getLogger(MobileVideoUploadProcessor.class);

    private final MobileVideoUploadRepository repository;
    private final MobileVideoTranscoder transcoder;
    private final TenantFairVideoScheduler scheduler;

    public MobileVideoUploadProcessor(
            MobileVideoUploadRepository repository,
            MobileVideoTranscoder transcoder,
            TenantFairVideoScheduler scheduler) {
        this.repository = repository;
        this.transcoder = transcoder;
        this.scheduler = scheduler;
    }

    public void normalize(UUID uploadId) {
        MobileVideoUpload snapshot = repository.findById(uploadId).orElse(null);
        if (snapshot == null) return;
        try {
            scheduler.submit(snapshot.getTenantId(), "mobile-normalize:" + uploadId, () -> normalizeNow(uploadId));
        } catch (RejectedExecutionException rejected) {
            log.error("Mobile normalization queue rejected task: uploadId={}, tenantId={}",
                    uploadId, snapshot.getTenantId(), rejected);
            repository.findById(uploadId).ifPresent(current -> {
                current.markError("Очередь обработки видео временно переполнена. Повторите попытку позже");
                repository.saveAndFlush(current);
            });
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedNormalizations() {
        List<MobileVideoUpload> interrupted = repository.findAllByStatusIn(
                List.of(MobileVideoUploadStatus.UPLOADED, MobileVideoUploadStatus.PROCESSING));
        if (interrupted.isEmpty()) return;
        log.info("Recovering interrupted mobile video normalizations: count={}", interrupted.size());
        for (MobileVideoUpload upload : interrupted) {
            if (upload.getStatus() == MobileVideoUploadStatus.PROCESSING) {
                upload.resetProcessingForRecovery();
                repository.saveAndFlush(upload);
            }
            normalize(upload.getId());
        }
    }

    private void normalizeNow(UUID uploadId) {
        MobileVideoUpload upload = repository.findById(uploadId).orElse(null);
        if (upload == null) {
            log.debug("Mobile video normalization skipped: upload not found, uploadId={}", uploadId);
            return;
        }
        if (upload.getStatus() == MobileVideoUploadStatus.READY
                || upload.getStatus() == MobileVideoUploadStatus.CONSUMED) {
            return;
        }
        if (upload.getStatus() != MobileVideoUploadStatus.UPLOADED) {
            log.warn("Mobile video normalization skipped because status is not UPLOADED: uploadId={}, status={}",
                    uploadId, upload.getStatus());
            return;
        }

        upload.markProcessing();
        repository.saveAndFlush(upload);

        Path input = Path.of(upload.getSourceFilePath());
        Path output = input.resolveSibling(uploadId + ".normalized.mp4");
        AtomicInteger persistedProgress = new AtomicInteger(0);
        try {
            MobileVideoTranscoder.TranscodeResult result = transcoder.transcode(
                    input,
                    output,
                    upload.getMimeType(),
                    percent -> persistProgress(uploadId, percent, persistedProgress));

            MobileVideoUpload current = repository.findById(uploadId).orElse(null);
            if (current == null) {
                // The user replaced/discarded this upload while FFmpeg was finishing. This is a
                // normal cancellation race, not an application error.
                cleanupOrphanResult(result.path(), input);
                log.info("Mobile video normalization result discarded because upload was removed: uploadId={}", uploadId);
                return;
            }
            if (current.getStatus() != MobileVideoUploadStatus.PROCESSING
                    && current.getStatus() != MobileVideoUploadStatus.UPLOADED) {
                cleanupOrphanResult(result.path(), input);
                log.info("Mobile video normalization result ignored because upload state changed: uploadId={}, status={}",
                        uploadId, current.getStatus());
                return;
            }

            current.markReady(result.path().toString(), result.size());
            repository.saveAndFlush(current);
            if (!samePath(result.path(), input)) Files.deleteIfExists(input);
            log.info("Mobile video upload READY: uploadId={}, bytes={}, path={}, quality={}",
                    uploadId, result.size(), result.path(), result.quality());
        } catch (Exception error) {
            MobileVideoUpload current = repository.findById(uploadId).orElse(null);
            if (current == null) {
                // User replacement/deletion can race with an already running processor.
                try { Files.deleteIfExists(output); } catch (Exception ignored) { }
                log.info("Mobile video processor stopped after upload removal: uploadId={}, detail={}",
                        uploadId, rootMessage(error));
                return;
            }
            log.error("Mobile video normalization failed: uploadId={}, error={}",
                    uploadId, error.getMessage(), error);
            current.markError(rootMessage(error));
            repository.saveAndFlush(current);
            try {
                Files.deleteIfExists(output);
            } catch (Exception cleanupError) {
                log.warn("Cannot remove failed normalized file: uploadId={}, error={}",
                        uploadId, cleanupError.getMessage());
            }
        }
    }

    private void persistProgress(UUID uploadId, int percent, AtomicInteger lastPersisted) {
        int normalized = Math.max(1, Math.min(99, percent));
        int previous = lastPersisted.get();
        if (normalized < 99 && normalized < previous + 2) return;
        if (!lastPersisted.compareAndSet(previous, normalized) && normalized <= lastPersisted.get()) return;
        repository.findById(uploadId).ifPresent(current -> {
            if (current.getStatus() == MobileVideoUploadStatus.PROCESSING) {
                current.markProcessingProgress(normalized);
                repository.saveAndFlush(current);
            }
        });
    }

    private void cleanupOrphanResult(Path result, Path input) {
        try {
            if (result != null && !samePath(result, input)) Files.deleteIfExists(result);
        } catch (Exception ignored) { }
    }

    private boolean samePath(Path first, Path second) {
        return first != null && second != null
                && first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize());
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
