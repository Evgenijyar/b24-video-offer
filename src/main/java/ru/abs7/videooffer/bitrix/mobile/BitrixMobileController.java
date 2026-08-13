package ru.abs7.videooffer.bitrix.mobile;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import ru.abs7.videooffer.bitrix.BitrixInstallationService;
import ru.abs7.videooffer.offer.CrmEntityType;
import ru.abs7.videooffer.tenant.TenantAccessService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/bitrix/mobile", "/bitrix/app"})
public class BitrixMobileController {
    private static final Logger log = LoggerFactory.getLogger(BitrixMobileController.class);

    private final BitrixInstallationService installationService;
    private final BitrixMobileContextSigner mobileContextSigner;
    private final BitrixCrmSearchService searchService;
    private final TenantAccessService accessService;
    private final String mobileTemplate;

    public BitrixMobileController(
            BitrixInstallationService installationService,
            BitrixMobileContextSigner mobileContextSigner,
            BitrixCrmSearchService searchService,
            TenantAccessService accessService) throws IOException {
        this.installationService = installationService;
        this.mobileContextSigner = mobileContextSigner;
        this.searchService = searchService;
        this.accessService = accessService;
        this.mobileTemplate = new ClassPathResource("static/bitrix-mobile.html")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @PostMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> openMobileApplication(
            @RequestParam MultiValueMap<String, String> parameters,
            HttpServletRequest servletRequest) {
        log.info("Bitrix standalone application opened: parameterNames={}, userAgent={}",
                parameters.keySet(), servletRequest.getHeader("User-Agent"));
        installationService.synchronizeAuthorizationFromWidget(parameters);
        String memberId = required(parameters, "member_id", "auth[member_id]", "MEMBER_ID");
        String domain = normalizeDomain(required(parameters, "DOMAIN", "domain", "auth[domain]", "auth[client_endpoint]"));
        String authId = required(parameters, "AUTH_ID", "auth[access_token]", "access_token");

        TenantAccessService.MobileAccessDecision access;
        try {
            access = accessService.resolveMobile(memberId, domain, authId);
        } catch (RuntimeException error) {
            log.warn("Mobile Video Offer access resolution failed: memberId={}, domain={}, error={}", memberId, domain, error.getMessage());
            return blocked("Не удалось проверить доступ", "Закройте Video Offer и откройте приложение из мобильного Bitrix24 ещё раз.");
        }
        if (!access.allowed()) {
            return blocked("Video Offer недоступен", access.message());
        }

        String token = mobileContextSigner.create(access.tenantId(), memberId, access.bitrixUserId(), access.admin());
        String html = mobileTemplate
                .replace("{{MOBILE_CONTEXT_TOKEN}}", escapeHtmlAttribute(token))
                .replace("{{MOBILE_IS_ADMIN}}", Boolean.toString(access.admin()))
                .replace("{{MOBILE_USER_NAME}}", escapeHtml(access.userName()))
                .replace("{{MOBILE_TENANT_NAME}}", escapeHtml(access.tenantName()));
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("Permissions-Policy", "camera=(self), microphone=(self)")
                .header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .body(html);
    }

    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> mobileApplicationInfo() {
        String html = """
                <!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Видео-оффер</title></head>
                <body style="font-family:Arial,sans-serif;padding:24px"><h1>Видео-оффер</h1><p>Откройте приложение из мобильного Bitrix24. При прямом открытии сервер не получает авторизационный контекст портала.</p></body></html>
                """;
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    @PostMapping(value = "/client-events", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> clientEvent(@RequestBody MobileClientEvent event, HttpServletRequest servletRequest) {
        BitrixMobileContextSigner.MobileActorContext actor = mobileContextSigner.verify(event.mobileContextToken());
        log.info("Bitrix mobile client event: tenantId={}, memberId={}, userId={}, event={}, details={}, userAgent={}",
                actor.tenantId(), actor.memberId(), actor.bitrixUserId(), safeLogValue(event.event(), 80),
                safeLogValue(event.details(), 1200), servletRequest.getHeader("User-Agent"));
        return Map.of("ok", true);
    }

    @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public BitrixCrmSearchResponse search(
            @RequestParam String mobileContextToken,
            @RequestParam CrmEntityType entityType,
            @RequestParam("q") String query) {
        BitrixMobileContextSigner.MobileActorContext actor = mobileContextSigner.verify(mobileContextToken);
        List<BitrixCrmSearchResult> results = searchService.search(actor, entityType, query);
        return new BitrixCrmSearchResponse(entityType, query.trim(), results);
    }

    private ResponseEntity<String> blocked(String title, String message) {
        String html = """
                <!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <style>body{margin:0;background:#f2f5f8;color:#263238;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Arial,sans-serif}.b{min-height:100vh;display:flex;align-items:center;justify-content:center;padding:22px}.c{width:100%%;max-width:480px;background:#fff;border:1px solid #e2e8ec;border-radius:18px;padding:26px;box-shadow:0 16px 44px rgba(31,45,61,.08)}h2{margin:0 0 10px;font-size:21px}p{margin:0;color:#6b7785;line-height:1.55}</style></head><body><div class="b"><div class="c"><h2>%s</h2><p>%s</p></div></div></body></html>
                """.formatted(escapeHtml(title), escapeHtml(message));
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).header("Cache-Control", "no-store").body(html);
    }

    private String required(MultiValueMap<String, String> parameters, String... names) {
        for (String name : names) {
            String value = parameters.getFirst(name);
            if (value != null && !value.isBlank()) return value.trim();
        }
        throw new IllegalArgumentException("Bitrix24 не передал обязательный параметр " + names[0]);
    }

    private String normalizeDomain(String value) {
        return value.trim().replaceFirst("^https?://", "").replaceFirst("/rest/.*$", "").replaceAll("/+$", "");
    }

    private String escapeHtmlAttribute(String value) { return escapeHtml(value).replace("'", "&#39;"); }
    private String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String safeLogValue(String value, int maxLength) {
        if (value == null) return "";
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "…";
    }

    public record MobileClientEvent(String mobileContextToken, String event, String details) {}
}
