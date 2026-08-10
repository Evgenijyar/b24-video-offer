package ru.abs7.videooffer.offer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateVideoOfferRequest(
        @NotNull CrmEntityType entityType,
        @NotNull @Positive Long entityId,
        String bitrixMemberId,
        Long bitrixUserId,
        @NotBlank String recordingUrl,
        String accompanyingText,
        String clientMessage,
        ViewNotificationGoal viewNotificationGoal) {}
