package ru.abs7.videooffer.bitrix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.abs7.videooffer.kontur.KonturTalkProperties;
import ru.abs7.videooffer.tenant.VideoOfferTenantRepository;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
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

    private static final Duration TOKEN_REFRESH_AHEAD = Duration.ofMinutes(2);

    private final BitrixInstallationRepository repository;
    private final BitrixProperties properties;
    private final VideoOfferTenantRepository tenantRepository;
    private final RestClient restClient;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public BitrixRestClient(
            BitrixInstallationRepository repository,
            BitrixProperties properties,
            KonturTalkProperties talkProperties,
            VideoOfferTenantRepository tenantRepository) {
        this.repository = repository;
        this.properties = properties;
        this.tenantRepository = tenantRepository;
        this.connectTimeout = Duration.ofSeconds(properties.connectTimeoutSecondsOrDefault());
        this.readTimeout = Duration.ofSeconds(properties.readTimeoutSecondsOrDefault());

        ResolvedProxy proxy = resolveProxy(properties, talkProperties);
        this.restClient = buildRestClient(proxy);

        log.info("Bitrix HTTP transport configured: mode={}, proxySource={}, proxyHost={}, proxyPort={}, "
                        + "proxyAuthenticationConfigured={}, connectTimeoutSeconds={}, readTimeoutSeconds={}, "
                        + "proactiveRefreshAheadSeconds={}",
                proxy.enabled() ? "HTTP_PROXY" : "DIRECT",
                proxy.source(),
                proxy.enabled() ? proxy.host() : "direct",
                proxy.enabled() ? proxy.port() : null,
                proxy.authenticationConfigured(),
                connectTimeout.toSeconds(),
                readTimeout.toSeconds(),
                TOKEN_REFRESH_AHEAD.toSeconds());
    }

    private RestClient buildRestClient(ResolvedProxy proxy) {
        RestClient.Builder builder = RestClient.builder();
        if (!proxy.enabled()) {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(connectTimeout);
            requestFactory.setReadTimeout(readTimeout);
            return builder.requestFactory(requestFactory).build();
        }

        enableJdkProxyAuthenticationSchemes();
        InetSocketAddress proxyAddress = InetSocketAddress.createUnresolved(proxy.host(), proxy.port());
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(ProxySelector.of(proxyAddress));

        if (proxy.authenticationConfigured()) {
            String username = proxy.username();
            char[] password = proxy.password().toCharArray();
            httpClientBuilder.authenticator(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() == RequestorType.PROXY) {
                        log.debug("Bitrix proxy requested authentication: proxyHost={}, proxyPort={}, scheme={}, prompt={}",
                                getRequestingHost(), getRequestingPort(), getRequestingScheme(), getRequestingPrompt());
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
            return new ResolvedProxy(
                    true,
                    explicit.host().trim(),
                    explicit.port(),
                    explicit.username(),
                    explicit.password(),
                    "BITRIX_PROXY");
        }

        KonturTalkProperties.ProxySettings talkProxy = talkProperties.proxyOrDefault();
        if (bitrixProperties.reuseTalkProxyOrDefault() && talkProxy.enabledOrDefault()) {
            validateProxy("app.talk.proxy", talkProxy.host(), talkProxy.port(),
                    talkProxy.usernameConfigured(), talkProxy.passwordConfigured());
            return new ResolvedProxy(
                    true,
                    talkProxy.host().trim(),
                    talkProxy.port(),
                    talkProxy.username(),
                    talkProxy.password(),
                    "TALK_PROXY");
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
        // Java may disable Basic authentication for HTTPS CONNECT by default.
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        System.setProperty("jdk.http.auth.proxying.disabledSchemes", "");
    }

    public Map<String, Object> call(
            String memberId,
            String method,
            Map<String, Object> parameters) {
        long startedAt = System.nanoTime();
        log.info("Bitrix REST call started: memberId={}, method={}, parameterNames={}",
                memberId, method, parameters.keySet());

        BitrixInstallation installation = repository.findByMemberId(memberId)
                .orElseThrow(() -> new IllegalStateException(
                        "Установка Bitrix24 не найдена для member_id=" + memberId));

        if (isTokenNearExpiry(installation)) {
            log.info("Bitrix access token is expired or near expiry; refreshing before REST call: "
                            + "memberId={}, method={}, tokenExpiresAt={}",
                    memberId, method, installation.getTokenExpiresAt());
            installation = refreshAuthorization(memberId, false);
        }

        try {
            Map<String, Object> response = execute(installation, method, parameters);
            log.info("Bitrix REST call completed: memberId={}, method={}, durationMs={}, responseKeys={}",
                    memberId, method, elapsedMillis(startedAt), response.keySet());
            return response;
        } catch (BitrixRestException error) {
            if (!isAuthorizationError(error)) {
                log.error("Bitrix REST call failed: memberId={}, method={}, errorCode={}, durationMs={}, error={}",
                        memberId,
                        method,
                        error.getErrorCode(),
                        elapsedMillis(startedAt),
                        error.getMessage(),
                        error);
                throw error;
            }

            log.warn("Bitrix REST authorization error, refreshing token and retrying once: "
                            + "memberId={}, method={}, errorCode={}, error={}",
                    memberId, method, error.getErrorCode(), error.getMessage());
            BitrixInstallation refreshed = refreshAuthorization(memberId, true);
            Map<String, Object> response = execute(refreshed, method, parameters);
            log.info("Bitrix REST call completed after token refresh: memberId={}, method={}, durationMs={}",
                    memberId, method, elapsedMillis(startedAt));
            return response;
        }
    }

    public Map<String, Object> callWithAccessToken(
            String portalDomain,
            String accessToken,
            String method,
            Map<String, Object> parameters) {
        if (portalDomain == null || portalDomain.isBlank()) {
            throw new IllegalArgumentException("Домен Bitrix24 не передан");
        }
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("AUTH_ID Bitrix24 не передан");
        }
        String domain = portalDomain.trim()
                .replaceFirst("^https?://", "")
                .replaceAll("/+$", "");
        Map<String, Object> body = new LinkedHashMap<>(parameters == null ? Map.of() : parameters);
        body.put("auth", accessToken.trim());
        String endpoint = "https://" + domain + "/rest/" + method + ".json";
        long startedAt = System.nanoTime();
        log.info("Bitrix current-user REST call started: domain={}, method={}, parameterNames={}",
                domain, method, body.keySet());
        Map<String, Object> response;
        try {
            response = restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(MAP_TYPE);
        } catch (RestClientResponseException error) {
            String responseBody = abbreviate(error.getResponseBodyAsString(), 2_000);
            throw new BitrixRestException(
                    "HTTP_" + error.getStatusCode().value(),
                    responseBody.isBlank() ? error.getMessage() : responseBody);
        }
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
        log.info("Bitrix current-user REST call completed: domain={}, method={}, durationMs={}",
                domain, method, elapsedMillis(startedAt));
        return response;
    }

    private Map<String, Object> execute(
            BitrixInstallation installation,
            String method,
            Map<String, Object> parameters) {
        long startedAt = System.nanoTime();
        Map<String, Object> body = new LinkedHashMap<>(parameters);
        body.put("auth", installation.getAccessToken());

        String endpoint = "https://" + installation.getPortalDomain()
                + "/rest/" + method + ".json";

        log.info("Executing Bitrix REST request: memberId={}, domain={}, method={}, endpoint={}, "
                        + "tokenExpiresAt={}",
                installation.getMemberId(),
                installation.getPortalDomain(),
                method,
                endpoint,
                installation.getTokenExpiresAt());

        Map<String, Object> response;
        try {
            response = restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(MAP_TYPE);
        } catch (RestClientResponseException error) {
            String responseBody = abbreviate(error.getResponseBodyAsString(), 2_000);
            log.error("Bitrix HTTP error response: memberId={}, method={}, endpoint={}, status={}, "
                            + "durationMs={}, responseBody={}",
                    installation.getMemberId(),
                    method,
                    endpoint,
                    error.getStatusCode(),
                    elapsedMillis(startedAt),
                    responseBody,
                    error);
            throw new BitrixRestException(
                    "HTTP_" + error.getStatusCode().value(),
                    responseBody.isBlank() ? error.getMessage() : responseBody);
        } catch (RuntimeException error) {
            log.error("Bitrix HTTP request failed or timed out: memberId={}, method={}, endpoint={}, "
                            + "durationMs={}, error={}",
                    installation.getMemberId(),
                    method,
                    endpoint,
                    elapsedMillis(startedAt),
                    rootMessage(error),
                    error);
            throw error;
        }

        log.info("Bitrix REST response received: memberId={}, method={}, durationMs={}, responseKeys={}",
                installation.getMemberId(),
                method,
                elapsedMillis(startedAt),
                response == null ? "null" : response.keySet());

        if (response == null) {
            throw new BitrixRestException("EMPTY_RESPONSE", "Bitrix24 вернул пустой ответ");
        }

        Object error = response.get("error");
        if (error != null) {
            String code = String.valueOf(error);
            String description = String.valueOf(
                    response.getOrDefault("error_description", "Ошибка Bitrix24: " + code));
            log.error("Bitrix REST returned API error: memberId={}, method={}, errorCode={}, description={}",
                    installation.getMemberId(), method, code, description);
            throw new BitrixRestException(code, description);
        }

        return response;
    }

    private synchronized BitrixInstallation refreshAuthorization(String memberId, boolean force) {
        long startedAt = System.nanoTime();
        BitrixInstallation installation = repository.findByMemberId(memberId)
                .orElseThrow(() -> new IllegalStateException(
                        "Установка Bitrix24 не найдена для member_id=" + memberId));

        // Другой поток мог успеть обновить пару токенов, пока текущий ждал synchronized-блок.
        if (!force && !isTokenNearExpiry(installation)) {
            log.info("Bitrix token refresh skipped because another thread already supplied a fresh token: "
                            + "memberId={}, expiresAt={}",
                    memberId, installation.getTokenExpiresAt());
            return installation;
        }

        log.info("Refreshing Bitrix OAuth tokens: memberId={}, force={}, currentExpiresAt={}",
                memberId, force, installation.getTokenExpiresAt());

        if (installation.getRefreshToken() == null || installation.getRefreshToken().isBlank()) {
            throw new IllegalStateException("У Bitrix24 отсутствует refresh_token");
        }

        String clientId = properties.clientId();
        String clientSecret = properties.clientSecret();
        var tenant = tenantRepository.findByMemberId(memberId)
                .or(() -> tenantRepository.findByPortalDomainIgnoreCase(installation.getPortalDomain()))
                .orElse(null);
        if (tenant != null && tenant.getLocalClientId() != null && !tenant.getLocalClientId().isBlank()
                && tenant.getLocalClientSecret() != null && !tenant.getLocalClientSecret().isBlank()) {
            clientId = tenant.getLocalClientId();
            clientSecret = tenant.getLocalClientSecret();
        }
        final String resolvedClientId = clientId;
        final String resolvedClientSecret = clientSecret;

        Map<String, Object> response;
        try {
            response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host("oauth.bitrix.info")
                            .path("/oauth/token/")
                            .queryParam("grant_type", "refresh_token")
                            .queryParam("client_id", resolvedClientId)
                            .queryParam("client_secret", resolvedClientSecret)
                            .queryParam("refresh_token", installation.getRefreshToken())
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(MAP_TYPE);
        } catch (RestClientResponseException error) {
            String responseBody = abbreviate(error.getResponseBodyAsString(), 2_000);
            log.error("Bitrix OAuth token refresh HTTP error: memberId={}, status={}, durationMs={}, "
                            + "responseBody={}",
                    memberId,
                    error.getStatusCode(),
                    elapsedMillis(startedAt),
                    responseBody,
                    error);
            throw new BitrixRestException(
                    "OAUTH_HTTP_" + error.getStatusCode().value(),
                    responseBody.isBlank() ? error.getMessage() : responseBody);
        } catch (RuntimeException error) {
            log.error("Bitrix OAuth token refresh HTTP request failed or timed out: "
                            + "memberId={}, durationMs={}, error={}",
                    memberId, elapsedMillis(startedAt), rootMessage(error), error);
            throw error;
        }

        if (response == null || response.get("error") != null) {
            String code = response == null
                    ? "EMPTY_RESPONSE"
                    : String.valueOf(response.get("error"));
            String description = response == null
                    ? "Bitrix24 вернул пустой ответ при обновлении токена"
                    : String.valueOf(response.getOrDefault("error_description", code));
            log.error("Bitrix OAuth token refresh failed: memberId={}, errorCode={}, description={}",
                    memberId, code, description);
            throw new BitrixRestException(code, description);
        }

        String accessToken = requiredString(response, "access_token");
        String refreshToken = requiredString(response, "refresh_token");
        OffsetDateTime expiresAt = parseExpiration(response);
        String domain = normalizeDomain(String.valueOf(
                response.getOrDefault("client_endpoint", installation.getPortalDomain())));

        // Bitrix каждый раз возвращает новую пару. Обязательно заменяем оба значения атомарно.
        installation.updateAuthorization(domain, accessToken, refreshToken, expiresAt);
        BitrixInstallation saved = repository.saveAndFlush(installation);
        log.info("OAuth-токены Bitrix24 обновлены: memberId={}, domain={}, expiresAt={}, durationMs={}",
                memberId, domain, expiresAt, elapsedMillis(startedAt));
        return saved;
    }

    private boolean isTokenNearExpiry(BitrixInstallation installation) {
        OffsetDateTime expiresAt = installation.getTokenExpiresAt();
        return expiresAt == null
                || !expiresAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC).plus(TOKEN_REFRESH_AHEAD));
    }

    private OffsetDateTime parseExpiration(Map<String, Object> response) {
        Long expiresEpoch = asLong(response.get("expires"));
        if (expiresEpoch != null) {
            return OffsetDateTime.ofInstant(Instant.ofEpochSecond(expiresEpoch), ZoneOffset.UTC);
        }

        Long expiresIn = asLong(response.get("expires_in"));
        if (expiresIn != null) {
            return OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(expiresIn);
        }

        return OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
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

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength) + "...";
    }

    private record ResolvedProxy(
            boolean enabled,
            String host,
            Integer port,
            String username,
            String password,
            String source) {

        private static ResolvedProxy direct() {
            return new ResolvedProxy(false, null, null, null, null, "NONE");
        }

        private boolean authenticationConfigured() {
            return username != null && !username.isBlank()
                    && password != null && !password.isBlank();
        }
    }

    private String normalizeDomain(String value) {
        String normalized = value == null ? "" : value.trim();
        normalized = normalized.replaceFirst("^https?://", "");
        normalized = normalized.replaceFirst("/rest/?$", "");
        normalized = normalized.replaceAll("/+$", "");
        return normalized;
    }
}
