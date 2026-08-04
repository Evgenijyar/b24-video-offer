package ru.abs7.videooffer.bitrix;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.bitrix")
public record BitrixProperties(
        String clientId,
        String clientSecret,
        String redirectUri) {
}
