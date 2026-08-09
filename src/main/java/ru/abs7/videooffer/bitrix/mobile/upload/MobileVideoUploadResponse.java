package ru.abs7.videooffer.bitrix.mobile.upload;

import ru.abs7.videooffer.offer.CrmEntityType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MobileVideoUploadResponse(
        UUID id,
        String uploadToken,
        CrmEntityType entityType,
        long entityId,
        MobileVideoUploadStatus status,
        long bytesReceived,
        int nextSequence,
        String errorMessage,
        String previewUrl,
        UUID videoOfferId,
        OffsetDateTime readyAt) {

    public static MobileVideoUploadResponse from(MobileVideoUpload upload) {
        String previewUrl = upload.getStatus() == MobileVideoUploadStatus.READY
                || upload.getStatus() == MobileVideoUploadStatus.CONSUMED
                ? "/bitrix/mobile/uploads/" + upload.getId() + "/preview?uploadToken=" + upload.getUploadToken()
                : null;
        return new MobileVideoUploadResponse(
                upload.getId(),
                upload.getUploadToken(),
                upload.getCrmEntityType(),
                upload.getCrmEntityId(),
                upload.getStatus(),
                upload.getBytesReceived(),
                upload.getNextSequence(),
                upload.getErrorMessage(),
                previewUrl,
                upload.getVideoOfferId(),
                upload.getReadyAt());
    }
}
