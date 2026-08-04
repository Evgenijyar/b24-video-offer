package ru.abs7.videooffer.offer;

public enum ViewNotificationGoal {
    NONE,
    ONE_MINUTE,
    HALF,
    COMPLETED;

    public static ViewNotificationGoal orDefault(ViewNotificationGoal value) {
        return value == null ? ONE_MINUTE : value;
    }

    public String russianDescription() {
        return switch (this) {
            case NONE -> "не уведомлять о просмотре";
            case ONE_MINUTE -> "клиент посмотрит одну минуту видео";
            case HALF -> "клиент досмотрит видео до середины";
            case COMPLETED -> "клиент досмотрит видео целиком";
        };
    }
}
