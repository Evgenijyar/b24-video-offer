package ru.abs7.videooffer.bitrix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
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
            "CRM_CONTACT_DETAIL_ACTIVITY",
            "CRM_DEAL_DETAIL_TOOLBAR",
            "CRM_LEAD_DETAIL_TOOLBAR",
            "CRM_CONTACT_DETAIL_TOOLBAR");

    private final BitrixInstallationRepository repository;
    private final BitrixRestClient restClient;
    private final BitrixPlacementDiagnosticsService diagnosticsService;
    private final String handlerUrl;

    public BitrixInstallationService(
            BitrixInstallationRepository repository,
            BitrixRestClient restClient,
            BitrixPlacementDiagnosticsService diagnosticsService,
            @Value("${app.public-base-url}") String publicBaseUrl) {
        this.repository = repository;
        this.restClient = restClient;
        this.diagnosticsService = diagnosticsService;
        this.handlerUrl = publicBaseUrl.replaceAll("/+$", "") + "/bitrix/widget";
    }

    public static List<String> desiredPlacements() {
        return PLACEMENTS;
    }

    public InstallationResult install(MultiValueMap<String, String> parameters) {
        log.info("Starting Bitrix installation: parameterNames={}", parameters.keySet());
        AuthData auth = AuthData.from(parameters);
        String applicationScopes = first(parameters,
                "APPLICATION_SCOPE", "scope", "auth[scope]");
        log.info("Bitrix auth data parsed: domain={}, memberId={}, expiresAt={}, applicationScopes={}, "
                        + "accessTokenPresent={}, refreshTokenPresent={}",
                auth.domain(), auth.memberId(), auth.expiresAt(), applicationScopes,
                auth.accessToken() != null && !auth.accessToken().isBlank(),
                auth.refreshToken() != null && !auth.refreshToken().isBlank());

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
        log.info("Bitrix installation authorization persisted: domain={}, memberId={}",
                auth.domain(), auth.memberId());

        Map<String, String> results = bindPlacements(auth.memberId());
        BitrixPlacementDiagnosticsService.PlacementDiagnostics diagnostics =
                diagnosticsService.diagnose(auth.memberId());

        log.info("Локальное приложение Bitrix24 установлено: domain={}, memberId={}, placements={}",
                auth.domain(), auth.memberId(), results);
        return new InstallationResult(
                auth.domain(),
                auth.memberId(),
                handlerUrl,
                applicationScopes,
                results,
                diagnostics);
    }

    public Map<String, String> bindPlacements(String memberId) {
        Map<String, String> results = new LinkedHashMap<>();
        for (String placement : PLACEMENTS) {
            try {
                log.info("Binding Bitrix placement: memberId={}, placement={}, handler={}",
                        memberId, placement, handlerUrl);
                restClient.call(memberId, "placement.bind", bindParameters(placement));
                results.put(placement, "BOUND");
                log.info("Bitrix placement bound: memberId={}, placement={}", memberId, placement);
            } catch (BitrixRestException error) {
                if (isAlreadyBound(error)) {
                    results.put(placement, "ALREADY_BOUND");
                    log.info("Bitrix placement already bound: memberId={}, placement={}",
                            memberId, placement);
                } else {
                    String status = "ERROR:" + error.getErrorCode() + ":" + safeMessage(error);
                    results.put(placement, status);
                    log.error("Bitrix placement binding failed but installation will continue: "
                                    + "memberId={}, placement={}, errorCode={}, error={}",
                            memberId, placement, error.getErrorCode(), error.getMessage(), error);
                }
            } catch (RuntimeException error) {
                String status = "TRANSPORT_ERROR:" + rootMessage(error);
                results.put(placement, status);
                log.error("Bitrix placement binding transport failure but installation will continue: "
                                + "memberId={}, placement={}, error={}",
                        memberId, placement, rootMessage(error), error);
            }
        }
        return results;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void rebindPlacementsAfterStartup() {
        List<BitrixInstallation> installations = repository.findAll();
        if (installations.isEmpty()) {
            log.info("Bitrix placement startup diagnostics skipped: no installations found");
            return;
        }

        log.info("Starting Bitrix placement rebind and diagnostics after application startup: count={}",
                installations.size());
        for (BitrixInstallation installation : installations) {
            try {
                Map<String, String> placements = bindPlacements(installation.getMemberId());
                BitrixPlacementDiagnosticsService.PlacementDiagnostics diagnostics =
                        diagnosticsService.diagnose(installation.getMemberId());
                log.info("Bitrix startup placement check completed: domain={}, memberId={}, placements={}, diagnostics={}",
                        installation.getPortalDomain(), installation.getMemberId(), placements, diagnostics);
            } catch (RuntimeException error) {
                log.error("Bitrix startup placement check failed: domain={}, memberId={}, error={}",
                        installation.getPortalDomain(), installation.getMemberId(),
                        rootMessage(error), error);
            }
        }
    }

    public void synchronizeAuthorizationFromWidget(MultiValueMap<String, String> parameters) {
        AuthData incoming = AuthData.from(parameters);
        BitrixInstallation installation = repository.findByMemberId(incoming.memberId())
                .orElseGet(() -> BitrixInstallation.create(
                        incoming.memberId(),
                        incoming.domain(),
                        incoming.accessToken(),
                        incoming.refreshToken(),
                        incoming.expiresAt()));

        OffsetDateTime storedExpiresAt = installation.getTokenExpiresAt();
        boolean tokenChanged = !incoming.accessToken().equals(installation.getAccessToken());
        boolean incomingIsNewer = storedExpiresAt == null
                || incoming.expiresAt().isAfter(storedExpiresAt.plusSeconds(5));
        boolean storedExpiredOrNearExpiry = storedExpiresAt == null
                || !storedExpiresAt.isAfter(OffsetDateTime.now().plusSeconds(30));

        if (installation.getId() == null
                || incomingIsNewer
                || (storedExpiredOrNearExpiry && tokenChanged)) {
            installation.updateAuthorization(
                    incoming.domain(),
                    incoming.accessToken(),
                    incoming.refreshToken(),
                    incoming.expiresAt());
            repository.saveAndFlush(installation);
            log.info("Fresh Bitrix authorization synchronized from widget callback: "
                            + "memberId={}, domain={}, expiresAt={}, tokenChanged={}",
                    incoming.memberId(), incoming.domain(), incoming.expiresAt(), tokenChanged);
        } else {
            log.debug("Widget authorization is not newer than stored authorization: "
                            + "memberId={}, incomingExpiresAt={}, storedExpiresAt={}",
                    incoming.memberId(), incoming.expiresAt(), storedExpiresAt);
        }
    }

    private Map<String, Object> bindParameters(String placement) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("PLACEMENT", placement);
        parameters.put("HANDLER", handlerUrl);
        parameters.put("TITLE", "Создать видеооффер");
        parameters.put("LANG_ALL", Map.of(
                "ru", Map.of("TITLE", "Создать видеооффер"),
                "en", Map.of("TITLE", "Create video offer")));
        return parameters;
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

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message.replace('\n', ' ').replace('\r', ' ');
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return safeMessage(current);
    }

    private static String first(
            MultiValueMap<String, String> parameters,
            String... names) {
        for (String name : names) {
            String value = parameters.getFirst(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    public record InstallationResult(
            String domain,
            String memberId,
            String handler,
            String applicationScopes,
            Map<String, String> placements,
            BitrixPlacementDiagnosticsService.PlacementDiagnostics diagnostics) {
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
