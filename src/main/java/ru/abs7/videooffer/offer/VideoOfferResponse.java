package ru.abs7.videooffer.offer;

import java.time.OffsetDateTime;
import java.util.UUID;

public record VideoOfferResponse(UUID id, String publicUrl, VideoOfferStatus status, int progressPercent,
                                 String errorMessage, OffsetDateTime createdAt, OffsetDateTime readyAt) {
    public static VideoOfferResponse from(VideoOffer offer, String baseUrl) {
        return new VideoOfferResponse(offer.getId(), baseUrl + "/o/" + offer.getPublicToken(), offer.getStatus(),
                offer.getProgressPercent(), offer.getErrorMessage(), offer.getCreatedAt(), offer.getReadyAt());
    }
}
