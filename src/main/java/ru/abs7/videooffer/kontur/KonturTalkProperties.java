package ru.abs7.videooffer.kontur;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix="app.talk")
public record KonturTalkProperties(String apiUrl, String apiToken) {}
