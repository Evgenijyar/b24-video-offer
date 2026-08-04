package ru.abs7.videooffer.analytics;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record TrackVideoProgressRequest(
        @NotBlank
        @Size(max = 100)
        @Pattern(regexp = "[A-Za-z0-9._:-]+")
        String sessionId,

        @NotNull
        @DecimalMin("0")
        @DecimalMax("86400")
        BigDecimal positionSeconds,

        @DecimalMin("0")
        @DecimalMax("86400")
        BigDecimal durationSeconds,

        @NotNull
        @DecimalMin("0")
        @DecimalMax("86400")
        BigDecimal watchedSeconds,

        @NotNull
        VideoPlaybackProgressEventType eventType) {
}
