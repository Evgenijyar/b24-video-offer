package ru.abs7.videooffer.bitrix;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import ru.abs7.videooffer.offer.CreateVideoOfferRequest;
import ru.abs7.videooffer.offer.CrmEntityType;
import ru.abs7.videooffer.offer.VideoOffer;
import ru.abs7.videooffer.offer.VideoOfferResponse;
import ru.abs7.videooffer.offer.VideoOfferService;
import ru.abs7.videooffer.tenant.TenantAccessService;
import ru.abs7.videooffer.tenant.VideoOfferTenantUser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/bitrix")
public class BitrixController {
    private static final Logger log = LoggerFactory.getLogger(BitrixController.class);
    private static final Pattern ID_PATTERN = Pattern.compile("\\\"ID\\\"\\s*:\\s*\\\"?(\\d+)\\\"?");

    private final BitrixInstallationService installationService;
    private final BitrixContextSigner contextSigner;
    private final VideoOfferService videoOfferService;
    private final TenantAccessService accessService;
    private final String widgetTemplate;
    private final String installTemplate;

    public BitrixController(
            BitrixInstallationService installationService,
            BitrixContextSigner contextSigner,
            VideoOfferService videoOfferService,
            TenantAccessService accessService) throws IOException {
        this.installationService = installationService;
        this.contextSigner = contextSigner;
        this.videoOfferService = videoOfferService;
        this.accessService = accessService;
        this.widgetTemplate = new ClassPathResource("static/bitrix-widget.html").getContentAsString(StandardCharsets.UTF_8);
        this.installTemplate = new ClassPathResource("static/bitrix-install.html").getContentAsString(StandardCharsets.UTF_8);
    }

    @PostMapping(value = "/install", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> install(@RequestParam MultiValueMap<String, String> parameters) {
        log.info("Bitrix installation callback received: parameterNames={}", parameters.keySet());
        BitrixInstallationService.InstallationResult result = installationService.install(parameters);
        log.info("Bitrix installation callback completed: domain={}, memberId={}, applicationScopes={}, placements={}. Returning browser installer page which calls BX24.installFinish().",
                result.domain(), result.memberId(), result.applicationScopes(), result.placements());
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(installTemplate);
    }

    @GetMapping(value = "/install", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> installInfo() {
        String html = """
                <!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Видео-оффер</title></head>
                <body style="font-family:Arial,sans-serif;padding:24px"><h1>Видео-оффер</h1><p>Это служебная страница установки. Откройте приложение из Bitrix24.</p></body></html>
                """;
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    @PostMapping(value = "/widget", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> widget(
            @RequestParam MultiValueMap<String, String> parameters,
            HttpServletRequest servletRequest) {
        log.info("Bitrix widget callback received: parameterNames={}", parameters.keySet());
        installationService.synchronizeAuthorizationFromWidget(parameters);
        String placement = required(parameters, "PLACEMENT");
        String memberId = required(parameters, "member_id");
        String placementOptions = required(parameters, "PLACEMENT_OPTIONS");
        String portalDomain = required(parameters, "DOMAIN");
        String currentAccessToken = required(parameters, "AUTH_ID");
        String applicationScope = optional(parameters, "APPLICATION_SCOPE");
        String userAgent = servletRequest.getHeader("User-Agent");

        CrmEntityType entityType = CrmEntityType.fromBitrixPlacement(placement);
        long entityId = extractEntityId(placementOptions);
        TenantAccessService.AccessDecision access;
        try {
            access = accessService.resolveDesktop(memberId, portalDomain, currentAccessToken, entityType, entityId);
        } catch (RuntimeException error) {
            log.warn("Bitrix Video Offer access resolution failed: memberId={}, domain={}, entityType={}, entityId={}, error={}",
                    memberId, portalDomain, entityType, entityId, error.getMessage());
            return blocked("Не удалось проверить доступ", "Bitrix24 не удалось проверить пользователя приложения. Закройте карточку и откройте её заново.");
        }
        if (!access.allowed()) {
            log.info("Bitrix Video Offer access denied: memberId={}, domain={}, entityType={}, entityId={}, code={}, message={}",
                    memberId, portalDomain, entityType, entityId, access.code(), access.message());
            if ("USER_NOT_ALLOWED".equals(access.code()) || "NOT_RESPONSIBLE".equals(access.code())) {
                return blocked("Ой, кажется, вы вошли не в ту дверь", null);
            }
            return blocked("Video Offer недоступен", access.message());
        }

        log.info("Bitrix widget context resolved: placement={}, memberId={}, tenantId={}, userId={}, entityType={}, entityId={}, responsibleId={}, admin={}, allowAnyEntity={}, applicationScope={}, userAgent={}",
                placement, memberId, access.tenantId(), access.bitrixUserId(), entityType, entityId,
                access.responsibleId(), access.admin(), access.allowAnyEntity(), applicationScope, userAgent);
        String contextToken = contextSigner.create(new BitrixPlacementContext(
                access.tenantId(), memberId, access.bitrixUserId(), entityType, entityId));

        String html = widgetTemplate
                .replace("{{CONTEXT_TOKEN}}", escapeHtmlAttribute(contextToken))
                .replace("{{ENTITY_TYPE}}", entityType.name())
                .replace("{{ENTITY_LABEL}}", entityType.russianLabel())
                .replace("{{ENTITY_ID}}", Long.toString(entityId))
                .replace("{{CURRENT_USER_ID}}", Long.toString(access.bitrixUserId()))
                .replace("{{CURRENT_USER_NAME}}", escapeHtml(access.userName()))
                .replace("{{IS_ADMIN}}", Boolean.toString(access.admin()))
                .replace("{{TENANT_NAME}}", escapeHtml(access.tenantName()));

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("Permissions-Policy", "camera=(self), microphone=(self), display-capture=(self)")
                .header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .body(html);
    }

    @GetMapping(value = "/client-message", produces = MediaType.APPLICATION_JSON_VALUE)
    public BitrixClientMessageResponse clientMessage(@RequestParam String contextToken) {
        BitrixPlacementContext context = contextSigner.verify(contextToken);
        TenantAccessService.EntityAccess access = accessService.assertContextCanCreate(context);
        Long templateUserId = access.responsibleId() == null ? context.bitrixUserId() : access.responsibleId();
        TenantAccessService.UserDefaults defaults = accessService.defaultsForUser(context.tenantId(), templateUserId);
        VideoOfferTenantUser templateUser;
        try {
            templateUser = accessService.requiredUser(context.tenantId(), templateUserId);
        } catch (RuntimeException ignored) {
            templateUser = accessService.requiredUser(context.tenantId(), context.bitrixUserId());
        }
        return BitrixClientMessageResponse.available(
                defaults.clientMessage(), defaults.accompanyingText(), templateUser.getBitrixUserId(), templateUser.getDisplayName());
    }

    @PostMapping(value = "/client-events", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> clientEvent(@RequestBody BitrixClientEvent event, HttpServletRequest servletRequest) {
        BitrixPlacementContext context = contextSigner.verify(event.contextToken());
        log.info("Bitrix desktop client event: tenantId={}, memberId={}, userId={}, entityType={}, entityId={}, event={}, details={}, userAgent={}",
                context.tenantId(), context.memberId(), context.bitrixUserId(), context.entityType(), context.entityId(),
                safeLogValue(event.event(), 80), safeLogValue(event.details(), 1600), servletRequest.getHeader("User-Agent"));
        return Map.of("ok", true);
    }

    @PostMapping(value = "/video-offers", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VideoOfferResponse> createFromBitrix(@Valid @RequestBody BitrixCreateVideoOfferRequest request) {
        BitrixPlacementContext context = contextSigner.verify(request.contextToken());
        // consumeOffer performs the authoritative server-side access/responsible/quota check under lock.
        accessService.consumeOffer(context);
        try {
            VideoOffer offer = videoOfferService.create(new CreateVideoOfferRequest(
                    context.entityType(), context.entityId(), context.memberId(), context.bitrixUserId(), context.tenantId(),
                    request.recordingUrl(), request.accompanyingText(), request.clientMessage(), request.viewNotificationGoal(),
                    request.pageTextValues(), request.pageFileDraftIds()));
            log.info("Bitrix video offer accepted: offerId={}, tenantId={}, userId={}, entityType={}, entityId={}, status={}",
                    offer.getId(), context.tenantId(), context.bitrixUserId(), offer.getCrmEntityType(), offer.getCrmEntityId(), offer.getStatus());
            return ResponseEntity.accepted().body(videoOfferService.response(offer));
        } catch (RuntimeException error) {
            accessService.releaseConsumedOffer(context);
            throw error;
        }
    }

    private ResponseEntity<String> blocked(String title, String message) {
        String subtitle = message == null || message.isBlank() ? "" : "<p>" + escapeHtml(message) + "</p>";
        String html = """
                <!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <style>body{margin:0;background:#f5f7f9;color:#263238;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Arial,sans-serif}.b{min-height:360px;display:flex;align-items:center;justify-content:center;padding:28px}.c{max-width:620px;background:#fff;border:1px solid #e5e9ec;border-radius:14px;padding:30px;box-shadow:0 14px 40px rgba(31,45,61,.08)}h2{margin:0;font-size:22px}p{margin:10px 0 0;color:#6b7785;line-height:1.55}</style></head><body><div class="b"><div class="c"><h2>%s</h2>%s</div></div></body></html>
                """.formatted(escapeHtml(title), subtitle);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).header("Cache-Control", "no-store").body(html);
    }

    private String safeLogValue(String value, int maxLength) {
        if (value == null) return "";
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "…";
    }

    public record BitrixClientEvent(String contextToken, String event, String details) { }

    private long extractEntityId(String placementOptions) {
        Matcher matcher = ID_PATTERN.matcher(placementOptions);
        if (!matcher.find()) throw new IllegalArgumentException("Bitrix24 не передал ID карточки в PLACEMENT_OPTIONS");
        long id = Long.parseLong(matcher.group(1));
        if (id <= 0) throw new IllegalArgumentException("Bitrix24 передал некорректный ID карточки");
        return id;
    }

    private String required(MultiValueMap<String, String> parameters, String name) {
        String value = parameters.getFirst(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Bitrix24 не передал обязательный параметр " + name);
        return value.trim();
    }

    private String optional(MultiValueMap<String, String> parameters, String name) {
        String value = parameters.getFirst(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String escapeHtmlAttribute(String value) { return escapeHtml(value).replace("'", "&#39;"); }
    private String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
