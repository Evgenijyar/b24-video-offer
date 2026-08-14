package ru.abs7.videooffer.bitrix;

import jakarta.validation.constraints.NotBlank;
import ru.abs7.videooffer.offer.ViewNotificationGoal;

import java.util.Map;

public record BitrixCreateVideoOfferRequest(
        @NotBlank String contextToken,
        @NotBlank String recordingUrl,
        String accompanyingText,
        String clientMessage,
        ViewNotificationGoal viewNotificationGoal,
        Map<String, String> pageTextValues,
        Map<String, String> pageFileDraftIds) {
}
