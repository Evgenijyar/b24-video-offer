package ru.abs7.videooffer.bitrix.mobile.upload;

import jakarta.validation.constraints.NotBlank;
import ru.abs7.videooffer.offer.ViewNotificationGoal;

public record CreateMobileVideoOfferRequest(
        @NotBlank String uploadToken,
        @NotBlank String contextToken,
        String accompanyingText,
        String clientMessage,
        ViewNotificationGoal viewNotificationGoal) {
}
