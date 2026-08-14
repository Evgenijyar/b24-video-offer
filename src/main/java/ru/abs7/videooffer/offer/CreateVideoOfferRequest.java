package ru.abs7.videooffer.offer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.Map;

public record CreateVideoOfferRequest(
        @NotNull CrmEntityType entityType,
        @NotNull @Positive Long entityId,
        String bitrixMemberId,
        Long bitrixUserId,
        Long tenantId,
        @NotBlank String recordingUrl,
        String accompanyingText,
        String clientMessage,
        ViewNotificationGoal viewNotificationGoal,
        Map<String, String> pageTextValues,
        Map<String, String> pageFileDraftIds) {}
