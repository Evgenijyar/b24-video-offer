package ru.abs7.videooffer.bitrix.mobile.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
public class MobileVideoUploadProcessor {
    private static final Logger log = LoggerFactory.getLogger(MobileVideoUploadProcessor.class);

    private final MobileVideoUploadRepository repository;
    private final MobileVideoTranscoder transcoder;

    public MobileVideoUploadProcessor(
            MobileVideoUploadRepository repository,
            MobileVideoTranscoder transcoder) {
        this.repository = repository;
        this.transcoder = transcoder;
    }

    @Async
    public void normalize(UUID uploadId) {
        MobileVideoUpload upload = repository.findById(uploadId).orElse(null);
        if (upload == null) {
            log.warn("Mobile video normalization skipped: upload not found, uploadId={}", uploadId);
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
        try {
            MobileVideoTranscoder.TranscodeResult result = transcoder.transcode(input, output);
            MobileVideoUpload current = repository.findById(uploadId).orElseThrow();
            current.markReady(result.path().toString());
            repository.saveAndFlush(current);
            Files.deleteIfExists(input);
            log.info("Mobile video upload READY: uploadId={}, bytes={}, path={}",
                    uploadId, result.size(), result.path());
        } catch (Exception error) {
            log.error("Mobile video normalization failed: uploadId={}, error={}",
                    uploadId, error.getMessage(), error);
            repository.findById(uploadId).ifPresent(current -> {
                current.markError(rootMessage(error));
                repository.saveAndFlush(current);
            });
            try {
                Files.deleteIfExists(output);
            } catch (Exception cleanupError) {
                log.warn("Cannot remove failed normalized file: uploadId={}, error={}",
                        uploadId, cleanupError.getMessage());
            }
        }
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
