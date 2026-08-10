package ru.abs7.videooffer.bitrix.mobile.upload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record MergeMobileVideoUploadsRequest(
        @NotBlank String contextToken,
        @NotEmpty @Size(max = 24) List<@Valid Segment> segments) {

    public record Segment(
            @NotNull UUID uploadId,
            @NotBlank String uploadToken) {
    }
}
