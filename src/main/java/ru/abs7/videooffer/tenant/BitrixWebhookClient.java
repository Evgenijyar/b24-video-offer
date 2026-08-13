package ru.abs7.videooffer.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import ru.abs7.videooffer.bitrix.BitrixProperties;
import ru.abs7.videooffer.bitrix.BitrixRestException;
import ru.abs7.videooffer.kontur.KonturTalkProperties;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class BitrixWebhookClient {
    private static final Logger log = LoggerFactory.getLogger(BitrixWebhookClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public BitrixWebhookClient(
            BitrixProperties bitrixProperties,
            KonturTalkProperties talkProperties,
            @Value("${app.bitrix.backoffice-connect-timeout-seconds:10}") int connectTimeoutSeconds,
            @Value("${app.bitrix.backoffice-read-timeout-seconds:20}") int readTimeoutSeconds) {
        Duration connectTimeout = Duration.ofSeconds(Math.max(1, connectTimeoutSeconds));
        Duration readTimeout = Duration.ofSeconds(Math.max(1, readTimeoutSeconds));
        ResolvedProxy proxy = resolveProxy(bitrixProperties, talkProperties);
        this.restClient = buildRestClient(proxy, connectTimeout, readTimeout);
        log.info("Bitrix webhook HTTP transport configured: mode={}, proxySource={}, proxyHost={}, proxyPort={}, "
                        + "proxyAuthenticationConfigured={}, connectTimeoutSeconds={}, readTimeoutSeconds={}",
                proxy.enabled() ? "HTTP_PROXY" : "DIRECT",
                proxy.source(),
                proxy.enabled() ? proxy.host() : "direct",
                proxy.enabled() ? proxy.port() : null,
                proxy.authenticationConfigured(),
                connectTimeout.toSeconds(),
                readTimeout.toSeconds());
    }

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
            if (response == null) {
                throw new BitrixRestException("EMPTY_RESPONSE", "Bitrix24 вернул пустой ответ");
            }
            if (response.get("error") != null) {
                throw new BitrixRestException(
                        String.valueOf(response.get("error")),
                        String.valueOf(response.getOrDefault("error_description", "Ошибка Bitrix24")));
            }
            log.info("Bitrix webhook call completed: host={}, method={}, durationMs={}",
                    URI.create(base).getHost(), method, elapsedMillis(startedAt));
            return response;
        } catch (RestClientResponseException error) {
            String body = error.getResponseBodyAsString();
            throw new BitrixRestException("HTTP_" + error.getStatusCode().value(),
                    body == null || body.isBlank() ? error.getMessage() : body);
        } catch (BitrixRestException error) {
            throw error;
        } catch (RestClientException error) {
            log.warn("Bitrix webhook transport failed: host={}, method={}, durationMs={}, error={}",
                    URI.create(base).getHost(), method, elapsedMillis(startedAt), rootMessage(error));
            throw new BitrixRestException("TRANSPORT_ERROR",
                    "Bitrix24 не ответил: " + rootMessage(error));
        }
    }

    public String normalizeWebhookUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Входящий вебхук не указан");
        }
        String normalized = value.trim();
        if (!normalized.endsWith("/")) normalized += "/";
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Некорректный URL входящего вебхука", error);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("Входящий вебхук должен использовать HTTPS");
        }
        if (uri.getPath() == null || !uri.getPath().matches(".*/rest/\\d+/[^/]+/")) {
            throw new IllegalArgumentException(
                    "Ожидается URL вида https://portal.bitrix24.ru/rest/USER_ID/TOKEN/");
        }
        return normalized;
    }

    private RestClient buildRestClient(
            ResolvedProxy proxy,
            Duration connectTimeout,
            Duration readTimeout) {
        RestClient.Builder builder = RestClient.builder();
        if (!proxy.enabled()) {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(connectTimeout);
            requestFactory.setReadTimeout(readTimeout);
            return builder.requestFactory(requestFactory).build();
        }

        enableJdkProxyAuthenticationSchemes();
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(ProxySelector.of(InetSocketAddress.createUnresolved(proxy.host(), proxy.port())));

        if (proxy.authenticationConfigured()) {
            String username = proxy.username();
            char[] password = proxy.password().toCharArray();
            httpClientBuilder.authenticator(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() == RequestorType.PROXY) {
                        return new PasswordAuthentication(username, password);
                    }
                    return null;
                }
            });
        }

        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClientBuilder.build());
        requestFactory.setReadTimeout(readTimeout);
        return builder.requestFactory(requestFactory).build();
    }

    private ResolvedProxy resolveProxy(
            BitrixProperties bitrixProperties,
            KonturTalkProperties talkProperties) {
        BitrixProperties.ProxySettings explicit = bitrixProperties.proxyOrDefault();
        if (explicit.enabledOrDefault()) {
            validateProxy("app.bitrix.proxy", explicit.host(), explicit.port(),
                    explicit.usernameConfigured(), explicit.passwordConfigured());
            return new ResolvedProxy(true, explicit.host().trim(), explicit.port(),
                    explicit.username(), explicit.password(), "BITRIX_PROXY");
        }

        KonturTalkProperties.ProxySettings talkProxy = talkProperties.proxyOrDefault();
        if (bitrixProperties.reuseTalkProxyOrDefault() && talkProxy.enabledOrDefault()) {
            validateProxy("app.talk.proxy", talkProxy.host(), talkProxy.port(),
                    talkProxy.usernameConfigured(), talkProxy.passwordConfigured());
            return new ResolvedProxy(true, talkProxy.host().trim(), talkProxy.port(),
                    talkProxy.username(), talkProxy.password(), "TALK_PROXY");
        }

        return ResolvedProxy.direct();
    }

    private void validateProxy(
            String prefix,
            String host,
            Integer port,
            boolean usernameConfigured,
            boolean passwordConfigured) {
        if (host == null || host.isBlank()) {
            throw new IllegalStateException(prefix + ".host must be configured when proxy is enabled");
        }
        if (port == null || port <= 0 || port > 65_535) {
            throw new IllegalStateException(prefix + ".port must be between 1 and 65535");
        }
        if (usernameConfigured != passwordConfigured) {
            throw new IllegalStateException(prefix + ".username and .password must be configured together");
        }
    }

    private void enableJdkProxyAuthenticationSchemes() {
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        System.setProperty("jdk.http.auth.proxying.disabledSchemes", "");
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record ResolvedProxy(
            boolean enabled,
            String host,
            Integer port,
            String username,
            String password,
            String source) {
        static ResolvedProxy direct() {
            return new ResolvedProxy(false, null, null, null, null, "NONE");
        }

        boolean authenticationConfigured() {
            return username != null && !username.isBlank() && password != null && !password.isBlank();
        }
    }
}
