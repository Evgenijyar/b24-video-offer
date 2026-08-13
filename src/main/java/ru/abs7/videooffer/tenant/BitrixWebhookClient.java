package ru.abs7.videooffer.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.abs7.videooffer.bitrix.BitrixRestException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class BitrixWebhookClient {
    private static final Logger log = LoggerFactory.getLogger(BitrixWebhookClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {};
    private final RestClient restClient = RestClient.create();

    public Map<String, Object> call(String webhookUrl, String method, Map<String, Object> parameters) {
        String base = normalizeWebhookUrl(webhookUrl);
        String endpoint = base + method + ".json";
        long startedAt = System.nanoTime();
        try {
            Map<String, Object> response = restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(parameters == null ? Map.of() : new LinkedHashMap<>(parameters))
                    .retrieve()
                    .body(MAP_TYPE);
            if (response == null) throw new BitrixRestException("EMPTY_RESPONSE", "Bitrix24 вернул пустой ответ");
            if (response.get("error") != null) {
                throw new BitrixRestException(
                        String.valueOf(response.get("error")),
                        String.valueOf(response.getOrDefault("error_description", "Ошибка Bitrix24")));
            }
            log.info("Bitrix webhook call completed: host={}, method={}, durationMs={}",
                    URI.create(base).getHost(), method, (System.nanoTime() - startedAt) / 1_000_000L);
            return response;
        } catch (RestClientResponseException error) {
            String body = error.getResponseBodyAsString();
            throw new BitrixRestException("HTTP_" + error.getStatusCode().value(),
                    body == null || body.isBlank() ? error.getMessage() : body);
        }
    }

    public String normalizeWebhookUrl(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Webhook / REST URL не указан");
        String normalized = value.trim();
        if (!normalized.endsWith("/")) normalized += "/";
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Некорректный Webhook / REST URL", error);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("Webhook Bitrix24 должен использовать HTTPS");
        }
        if (uri.getPath() == null || !uri.getPath().matches(".*/rest/\\d+/[^/]+/")) {
            throw new IllegalArgumentException("Ожидается входящий webhook вида https://portal.bitrix24.ru/rest/USER_ID/TOKEN/");
        }
        return normalized;
    }
}
