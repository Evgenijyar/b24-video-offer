package ru.abs7.videooffer.analytics;

import ru.abs7.videooffer.offer.ViewNotificationStatus;

public record VideoViewProgressResponse(
        boolean trackingActive,
        boolean goalReached,
        ViewNotificationStatus notificationStatus) {
}
