package ru.abs7.videooffer.bitrix;

import jakarta.validation.constraints.NotBlank;

public record BitrixCreateVideoOfferRequest(
        @NotBlank String contextToken,
        @NotBlank String recordingUrl,
        String accompanyingText) {
}
