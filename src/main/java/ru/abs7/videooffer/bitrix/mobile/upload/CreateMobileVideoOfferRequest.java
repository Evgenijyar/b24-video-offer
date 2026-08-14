package ru.abs7.videooffer.bitrix.mobile.upload;

import jakarta.validation.constraints.NotBlank;
import ru.abs7.videooffer.offer.ViewNotificationGoal;

import java.util.Map;

public record CreateMobileVideoOfferRequest(
        @NotBlank String uploadToken,
        @NotBlank String contextToken,
        String accompanyingText,
        String clientMessage,
        ViewNotificationGoal viewNotificationGoal,
        Map<String, String> pageTextValues,
        Map<String, String> pageFileDraftIds) {
}
