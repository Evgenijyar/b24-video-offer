package ru.abs7.videooffer.bitrix.mobile.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MobileVideoUploadCleanupService {
    private static final Logger log = LoggerFactory.getLogger(MobileVideoUploadCleanupService.class);

    private final MobileVideoUploadService service;

    public MobileVideoUploadCleanupService(MobileVideoUploadService service) {
        this.service = service;
    }

    @Scheduled(cron = "${app.mobile-video.cleanup-cron:0 */30 * * * *}")
    public void cleanup() {
        try {
            service.cleanupExpired();
        } catch (Exception error) {
            log.error("Mobile video upload cleanup failed: {}", error.getMessage(), error);
        }
    }
}
