package ru.abs7.videooffer.publicpage;

import ru.abs7.videooffer.offer.VideoOffer;
import ru.abs7.videooffer.offer.VideoOfferStatus;

import java.time.OffsetDateTime;

public record PublicOfferResponse(
        String token,
        VideoOfferStatus status,
        int progressPercent,
        String text,
        boolean ready,
        OffsetDateTime createdAt) {

    public static PublicOfferResponse from(VideoOffer offer) {
        return new PublicOfferResponse(
                offer.getPublicToken(),
                offer.getStatus(),
                offer.getProgressPercent(),
                offer.getAccompanyingText() == null ? "" : offer.getAccompanyingText(),
                offer.getStatus() == VideoOfferStatus.READY,
                offer.getCreatedAt());
    }
}
