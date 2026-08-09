package ru.abs7.videooffer.bitrix.mobile.upload;

import jakarta.validation.constraints.NotBlank;

public record CreateMobileVideoUploadRequest(
        @NotBlank String contextToken,
        @NotBlank String mimeType) {
}
