package ru.abs7.videooffer.common;

import java.time.OffsetDateTime;

public record ApiError(String message, OffsetDateTime time) {
    public static ApiError of(String message) {
        return new ApiError(message, OffsetDateTime.now());
    }
}
