package ru.abs7.videooffer.offer;

import java.time.OffsetDateTime;
import java.util.UUID;

public record VideoOfferResponse(
        UUID id,
        CrmEntityType entityType,
        long entityId,
        String publicUrl,
        String relativePath,
        VideoOfferStatus status,
        int progressPercent,
        String videoQuality,
        Long videoFileSize,
        String errorMessage,
        String bitrixDeliveryStatus,
        String bitrixDeliveryError,
        OffsetDateTime bitrixDeliveredAt,
        OffsetDateTime createdAt,
        OffsetDateTime readyAt) {

    public static VideoOfferResponse from(VideoOffer offer, String baseUrl) {
        String relativePath = "/o/" + offer.getPublicToken();
        return new VideoOfferResponse(
                offer.getId(),
                offer.getCrmEntityType(),
                offer.getCrmEntityId(),
                baseUrl + relativePath,
                relativePath,
                offer.getStatus(),
                offer.getProgressPercent(),
                offer.getVideoQuality(),
                offer.getVideoFileSize(),
                offer.getErrorMessage(),
                offer.getBitrixDeliveryStatus(),
                offer.getBitrixDeliveryError(),
                offer.getBitrixDeliveredAt(),
                offer.getCreatedAt(),
                offer.getReadyAt());
    }
}
