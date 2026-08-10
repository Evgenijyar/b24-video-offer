package ru.abs7.videooffer.bitrix;

import jakarta.validation.constraints.NotBlank;
import ru.abs7.videooffer.offer.ViewNotificationGoal;

public record BitrixCreateVideoOfferRequest(
        @NotBlank String contextToken,
        @NotBlank String recordingUrl,
        String accompanyingText,
        String clientMessage,
        ViewNotificationGoal viewNotificationGoal) {
}
