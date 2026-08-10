package ru.abs7.videooffer.bitrix.mobile.upload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

public record CreateMobileVideoUploadRequest(
        @NotBlank String contextToken,
        @NotBlank String mimeType,
        MobileVideoSourceKind sourceKind,
        @PositiveOrZero Long declaredSizeBytes) {
}
