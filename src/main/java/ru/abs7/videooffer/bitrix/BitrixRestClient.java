package ru.abs7.videooffer.bitrix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class BitrixRestClient {
    private static final Logger log = LoggerFactory.getLogger(BitrixRestClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final BitrixInstallationRepository repository;
    private final BitrixProperties properties;
    private final RestClient restClient;

    public BitrixRestClient(
            BitrixInstallationRepository repository,
            BitrixProperties properties) {
        this.repository = repository;
        this.properties = properties;
        this.restClient = RestClient.builder().build();
    }

    public Map<String, Object> call(String memberId, String method, Map<String, Object> parameters) {
        BitrixInstallation installation = repository.findByMemberId(memberId)
                .orElseThrow(() -> new IllegalStateException(
                        "Установка Bitrix24 не найдена для member_id=" + memberId));

        try {
            return execute(installation, method, parameters);
        } catch (BitrixRestException error) {
            if (!isAuthorizationError(error)) {
                throw error;
            }

            BitrixInstallation refreshed = refreshAuthorization(memberId);
            return execute(refreshed, method, parameters);
        }
    }

    private Map<String, Object> execute(
            BitrixInstallation installation,
            String method,
            Map<String, Object> parameters) {
        Map<String, Object> body = new LinkedHashMap<>(parameters);
        body.put("auth", installation.getAccessToken());

        String endpoint = "https://" + installation.getPortalDomain()
                + "/rest/" + method + ".json";

        Map<String, Object> response = restClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(MAP_TYPE);

        if (response == null) {
            throw new BitrixRestException("EMPTY_RESPONSE", "Bitrix24 вернул пустой ответ");
        }

        Object error = response.get("error");
        if (error != null) {
            String code = String.valueOf(error);
            String description = String.valueOf(
                    response.getOrDefault("error_description", "Ошибка Bitrix24: " + code));
            throw new BitrixRestException(code, description);
        }

        return response;
    }

    private synchronized BitrixInstallation refreshAuthorization(String memberId) {
        BitrixInstallation installation = repository.findByMemberId(memberId)
                .orElseThrow(() -> new IllegalStateException(
                        "Установка Bitrix24 не найдена для member_id=" + memberId));

        if (installation.getRefreshToken() == null || installation.getRefreshToken().isBlank()) {
            throw new IllegalStateException("У Bitrix24 отсутствует refresh_token");
        }

        Map<String, Object> response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https")
                        .host("oauth.bitrix.info")
                        .path("/oauth/token/")
                        .queryParam("grant_type", "refresh_token")
                        .queryParam("client_id", properties.clientId())
                        .queryParam("client_secret", properties.clientSecret())
                        .queryParam("refresh_token", installation.getRefreshToken())
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(MAP_TYPE);

        if (response == null || response.get("error") != null) {
            String code = response == null ? "EMPTY_RESPONSE" : String.valueOf(response.get("error"));
            String description = response == null
                    ? "Bitrix24 вернул пустой ответ при обновлении токена"
                    : String.valueOf(response.getOrDefault("error_description", code));
            throw new BitrixRestException(code, description);
        }

        String accessToken = requiredString(response, "access_token");
        String refreshToken = requiredString(response, "refresh_token");
        OffsetDateTime expiresAt = parseExpiration(response);
        String domain = normalizeDomain(String.valueOf(
                response.getOrDefault("client_endpoint", installation.getPortalDomain())));

        installation.updateAuthorization(domain, accessToken, refreshToken, expiresAt);
        BitrixInstallation saved = repository.saveAndFlush(installation);
        log.info("OAuth-токены Bitrix24 обновлены, memberId={}", memberId);
        return saved;
    }

    private OffsetDateTime parseExpiration(Map<String, Object> response) {
        Object expires = response.get("expires");
        if (expires instanceof Number number) {
            return OffsetDateTime.ofInstant(
                    Instant.ofEpochSecond(number.longValue()), ZoneOffset.UTC);
        }

        Object expiresIn = response.get("expires_in");
        if (expiresIn instanceof Number number) {
            return OffsetDateTime.now().plusSeconds(number.longValue());
        }

        return OffsetDateTime.now().plusHours(1);
    }

    private String requiredString(Map<String, Object> response, String key) {
        Object value = response.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalStateException("В ответе Bitrix24 отсутствует поле " + key);
        }
        return String.valueOf(value);
    }

    private boolean isAuthorizationError(BitrixRestException error) {
        String code = error.getErrorCode().toUpperCase(Locale.ROOT);
        String message = error.getMessage() == null
                ? ""
                : error.getMessage().toUpperCase(Locale.ROOT);
        return code.contains("AUTH")
                || code.contains("TOKEN")
                || code.contains("EXPIRED")
                || message.contains("AUTH")
                || message.contains("TOKEN")
                || message.contains("EXPIRED");
    }

    private String normalizeDomain(String value) {
        String normalized = value == null ? "" : value.trim();
        normalized = normalized.replaceFirst("^https?://", "");
        normalized = normalized.replaceFirst("/rest/?$", "");
        normalized = normalized.replaceAll("/+$", "");
        return normalized;
    }
}
