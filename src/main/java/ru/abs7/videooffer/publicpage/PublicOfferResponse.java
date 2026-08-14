package ru.abs7.videooffer.publicpage;

import ru.abs7.videooffer.offer.VideoOffer;
import ru.abs7.videooffer.offer.VideoOfferStatus;
import ru.abs7.videooffer.offer.ViewNotificationGoal;
import ru.abs7.videooffer.tenant.PageTemplateService;

import java.time.OffsetDateTime;

public record PublicOfferResponse(
        String token,
        VideoOfferStatus status,
        int progressPercent,
        String text,
        boolean ready,
        boolean viewTrackingActive,
        ViewNotificationGoal viewNotificationGoal,
        OffsetDateTime createdAt,
        PageTemplateService.PageTemplateView pageTemplate,
        PageTemplateService.OfferPageContent pageContent) {

    public static PublicOfferResponse from(VideoOffer offer, PageTemplateService pageTemplateService) {
        return new PublicOfferResponse(
                offer.getPublicToken(),
                offer.getStatus(),
                offer.getProgressPercent(),
                offer.getAccompanyingText() == null ? "" : offer.getAccompanyingText(),
                offer.getStatus() == VideoOfferStatus.READY,
                offer.getStatus() == VideoOfferStatus.READY
                        && offer.getViewNotificationGoal() != ViewNotificationGoal.NONE
                        && offer.getViewGoalReachedAt() == null,
                offer.getViewNotificationGoal(),
                offer.getCreatedAt(),
                pageTemplateService.templateForOffer(offer),
                pageTemplateService.contentForOffer(offer));
    }
}
