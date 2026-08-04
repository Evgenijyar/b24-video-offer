package ru.abs7.videooffer.bitrix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class BitrixInstallationService {
    private static final Logger log = LoggerFactory.getLogger(BitrixInstallationService.class);
    private static final List<String> PLACEMENTS = List.of(
            "CRM_DEAL_DETAIL_ACTIVITY",
            "CRM_LEAD_DETAIL_ACTIVITY",
            "CRM_CONTACT_DETAIL_ACTIVITY");

    private final BitrixInstallationRepository repository;
    private final BitrixRestClient restClient;
    private final String handlerUrl;

    public BitrixInstallationService(
            BitrixInstallationRepository repository,
            BitrixRestClient restClient,
            @Value("${app.public-base-url}") String publicBaseUrl) {
        this.repository = repository;
        this.restClient = restClient;
        this.handlerUrl = publicBaseUrl.replaceAll("/+$", "") + "/bitrix/widget";
    }

    public InstallationResult install(MultiValueMap<String, String> parameters) {
        AuthData auth = AuthData.from(parameters);
        BitrixInstallation installation = repository.findByMemberId(auth.memberId())
                .orElseGet(() -> BitrixInstallation.create(
                        auth.memberId(),
                        auth.domain(),
                        auth.accessToken(),
                        auth.refreshToken(),
                        auth.expiresAt()));

        installation.updateAuthorization(
                auth.domain(), auth.accessToken(), auth.refreshToken(), auth.expiresAt());
        repository.saveAndFlush(installation);

        Map<String, String> results = new LinkedHashMap<>();
        for (String placement : PLACEMENTS) {
            try {
                restClient.call(auth.memberId(), "placement.bind", Map.of(
                        "PLACEMENT", placement,
                        "HANDLER", handlerUrl,
                        "TITLE", "Сформировать видеооффер"));
                results.put(placement, "BOUND");
            } catch (BitrixRestException error) {
                if (isAlreadyBound(error)) {
                    results.put(placement, "ALREADY_BOUND");
                } else {
                    throw error;
                }
            }
        }

        log.info("Локальное приложение Bitrix24 установлено: domain={}, memberId={}",
                auth.domain(), auth.memberId());
        return new InstallationResult(auth.domain(), auth.memberId(), handlerUrl, results);
    }

    private boolean isAlreadyBound(BitrixRestException error) {
        String code = error.getErrorCode().toUpperCase(Locale.ROOT);
        String description = error.getMessage() == null
                ? ""
                : error.getMessage().toUpperCase(Locale.ROOT);
        return code.contains("ALREADY")
                || code.contains("EXIST")
                || description.contains("ALREADY")
                || description.contains("УЖЕ");
    }

    public record InstallationResult(
            String domain,
            String memberId,
            String handler,
            Map<String, String> placements) {
    }

    private record AuthData(
            String memberId,
            String domain,
            String accessToken,
            String refreshToken,
            OffsetDateTime expiresAt) {

        private static AuthData from(MultiValueMap<String, String> parameters) {
            String memberId = required(parameters,
                    "auth[member_id]", "member_id", "MEMBER_ID");
            String domain = normalizeDomain(required(parameters,
                    "auth[client_endpoint]", "DOMAIN", "domain", "auth[domain]"));
            String accessToken = required(parameters,
                    "auth[access_token]", "AUTH_ID", "access_token");
            String refreshToken = required(parameters,
                    "auth[refresh_token]", "REFRESH_ID", "refresh_token");
            OffsetDateTime expiresAt = expiration(parameters);
            return new AuthData(memberId, domain, accessToken, refreshToken, expiresAt);
        }

        private static OffsetDateTime expiration(MultiValueMap<String, String> parameters) {
            String epoch = first(parameters, "auth[expires]", "expires");
            if (epoch != null) {
                try {
                    return OffsetDateTime.ofInstant(
                            Instant.ofEpochSecond(Long.parseLong(epoch)), ZoneOffset.UTC);
                } catch (NumberFormatException ignored) {
                    // fallback below
                }
            }

            String seconds = first(parameters,
                    "auth[expires_in]", "AUTH_EXPIRES", "expires_in");
            if (seconds != null) {
                try {
                    return OffsetDateTime.now().plusSeconds(Long.parseLong(seconds));
                } catch (NumberFormatException ignored) {
                    // fallback below
                }
            }
            return OffsetDateTime.now().plusHours(1);
        }

        private static String required(
                MultiValueMap<String, String> parameters,
                String... names) {
            String value = first(parameters, names);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "В запросе Bitrix24 отсутствует обязательный параметр: " + names[0]);
            }
            return value.trim();
        }

        private static String first(
                MultiValueMap<String, String> parameters,
                String... names) {
            for (String name : names) {
                String value = parameters.getFirst(name);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return null;
        }

        private static String normalizeDomain(String value) {
            String normalized = value.trim()
                    .replaceFirst("^https?://", "")
                    .replaceFirst("/rest/?$", "")
                    .replaceAll("/+$", "");
            if (normalized.isBlank()) {
                throw new IllegalArgumentException("Bitrix24 передал пустой DOMAIN");
            }
            return normalized;
        }
    }
}
