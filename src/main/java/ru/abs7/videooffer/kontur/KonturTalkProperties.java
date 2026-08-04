package ru.abs7.videooffer.kontur;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.talk")
public record KonturTalkProperties(
        String apiUrl,
        String apiToken,
        Integer connectTimeoutSeconds,
        Integer readTimeoutSeconds,
        Integer stallWarningSeconds,
        Integer progressLogStepPercent,
        Integer progressLogIntervalSeconds) {

    public int connectTimeoutSecondsOrDefault() {
        return positiveOrDefault(connectTimeoutSeconds, 15);
    }

    public int readTimeoutSecondsOrDefault() {
        return positiveOrDefault(readTimeoutSeconds, 120);
    }

    public int stallWarningSecondsOrDefault() {
        return positiveOrDefault(stallWarningSeconds, 30);
    }

    public int progressLogStepPercentOrDefault() {
        return positiveOrDefault(progressLogStepPercent, 5);
    }

    public int progressLogIntervalSecondsOrDefault() {
        return positiveOrDefault(progressLogIntervalSeconds, 20);
    }

    private int positiveOrDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
