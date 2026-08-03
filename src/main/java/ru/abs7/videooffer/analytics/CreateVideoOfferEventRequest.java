package ru.abs7.videooffer.analytics;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateVideoOfferEventRequest(
        @NotNull VideoOfferEventType eventType,
        BigDecimal playbackPositionSeconds) {
}
