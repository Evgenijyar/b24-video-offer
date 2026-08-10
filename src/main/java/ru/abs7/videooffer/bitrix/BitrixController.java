package ru.abs7.videooffer.bitrix;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.abs7.videooffer.offer.CreateVideoOfferRequest;
import ru.abs7.videooffer.offer.CrmEntityType;
import ru.abs7.videooffer.offer.VideoOffer;
import ru.abs7.videooffer.offer.VideoOfferResponse;
import ru.abs7.videooffer.offer.VideoOfferService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/bitrix")
public class BitrixController {
    private static final Logger log = LoggerFactory.getLogger(BitrixController.class);
    private static final Pattern ID_PATTERN = Pattern.compile(
            "\\\"ID\\\"\\s*:\\s*\\\"?(\\d+)\\\"?");

    private final BitrixInstallationService installationService;
    private final BitrixContextSigner contextSigner;
    private final VideoOfferService videoOfferService;
    private final BitrixResponsibleEmployeeService responsibleEmployeeService;
    private final String widgetTemplate;
    private final String installTemplate;

    public BitrixController(
            BitrixInstallationService installationService,
            BitrixContextSigner contextSigner,
            VideoOfferService videoOfferService,
            BitrixResponsibleEmployeeService responsibleEmployeeService) throws IOException {
        this.installationService = installationService;
        this.contextSigner = contextSigner;
        this.videoOfferService = videoOfferService;
        this.responsibleEmployeeService = responsibleEmployeeService;
        this.widgetTemplate = new ClassPathResource("static/bitrix-widget.html")
                .getContentAsString(StandardCharsets.UTF_8);
        this.installTemplate = new ClassPathResource("static/bitrix-install.html")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @PostMapping(
            value = "/install",
            produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> install(
            @RequestParam MultiValueMap<String, String> parameters) {
        log.info("Bitrix installation callback received: parameterNames={}", parameters.keySet());
        BitrixInstallationService.InstallationResult result = installationService.install(parameters);
        log.info("Bitrix installation callback completed: domain={}, memberId={}, applicationScopes={}, placements={}. "
                        + "Returning browser installer page which calls BX24.installFinish().",
                result.domain(), result.memberId(), result.applicationScopes(), result.placements());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(installTemplate);
    }

    @GetMapping(value = "/install", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> installInfo() {
        log.info("Bitrix installation endpoint opened with GET");
        String html = """
                <!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Видео-оффер</title></head>
                <body style=\"font-family:Arial,sans-serif;padding:24px\"><h1>Видео-оффер</h1><p>Это служебная страница установки. Откройте приложение из Bitrix24.</p></body></html>
                """;
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    @PostMapping(
            value = "/widget",
            produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> widget(
            @RequestParam MultiValueMap<String, String> parameters,
            HttpServletRequest servletRequest) {
        log.info("Bitrix widget callback received: parameterNames={}", parameters.keySet());
        installationService.synchronizeAuthorizationFromWidget(parameters);
        String placement = required(parameters, "PLACEMENT");
        String memberId = required(parameters, "member_id");
        String placementOptions = required(parameters, "PLACEMENT_OPTIONS");
        String applicationScope = optional(parameters, "APPLICATION_SCOPE");
        String userAgent = servletRequest.getHeader("User-Agent");

        CrmEntityType entityType = CrmEntityType.fromBitrixPlacement(placement);
        long entityId = extractEntityId(placementOptions);
        log.info("Bitrix widget context resolved: placement={}, memberId={}, entityType={}, entityId={}, "
                        + "applicationScope={}, userAgent={}",
                placement, memberId, entityType, entityId, applicationScope, userAgent);
        String contextToken = contextSigner.create(
                new BitrixPlacementContext(memberId, entityType, entityId));

        String html = widgetTemplate
                .replace("{{CONTEXT_TOKEN}}", escapeHtmlAttribute(contextToken))
                .replace("{{ENTITY_TYPE}}", entityType.name())
                .replace("{{ENTITY_LABEL}}", entityType.russianLabel())
                .replace("{{ENTITY_ID}}", Long.toString(entityId));

        log.info("Bitrix widget HTML generated: placement={}, entityType={}, entityId={}, htmlBytes={}",
                placement, entityType, entityId, html.getBytes(StandardCharsets.UTF_8).length);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("Permissions-Policy", "camera=(self), microphone=(self), display-capture=(self)")
                .header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .body(html);
    }

    @GetMapping(value = "/client-message", produces = MediaType.APPLICATION_JSON_VALUE)
    public BitrixClientMessageResponse clientMessage(
            @RequestParam String contextToken) {
        BitrixPlacementContext context = contextSigner.verify(contextToken);
        try {
            return BitrixClientMessageResponse.available(
                    responsibleEmployeeService.buildClientMessageTemplate(
                            context.memberId(), context.entityType(), context.entityId()));
        } catch (BitrixRestException error) {
            String warning = "Контакты ответственного сотрудника недоступны";
            if ("INSUFFICIENT_SCOPE".equalsIgnoreCase(error.getErrorCode())
                    || "insufficient_scope".equalsIgnoreCase(error.getErrorCode())) {
                warning = "Для контактов ответственного сотрудника приложению требуется scope user_basic";
            }
            log.warn("Bitrix client message generated without employee contacts: memberId={}, entityType={}, entityId={}, errorCode={}, error={}",
                    context.memberId(), context.entityType(), context.entityId(),
                    error.getErrorCode(), error.getMessage());
            return BitrixClientMessageResponse.fallback(
                    responsibleEmployeeService.fallbackTemplate(), warning);
        } catch (RuntimeException error) {
            log.warn("Bitrix client message generated with fallback: memberId={}, entityType={}, entityId={}, error={}",
                    context.memberId(), context.entityType(), context.entityId(), error.getMessage());
            return BitrixClientMessageResponse.fallback(
                    responsibleEmployeeService.fallbackTemplate(),
                    "Не удалось получить контакты ответственного сотрудника");
        }
    }

    @PostMapping(
            value = "/client-events",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> clientEvent(
            @RequestBody BitrixClientEvent event,
            HttpServletRequest servletRequest) {
        BitrixPlacementContext context = contextSigner.verify(event.contextToken());
        log.info("Bitrix desktop client event: memberId={}, entityType={}, entityId={}, event={}, details={}, userAgent={}",
                context.memberId(), context.entityType(), context.entityId(),
                safeLogValue(event.event(), 80), safeLogValue(event.details(), 1600),
                servletRequest.getHeader("User-Agent"));
        return Map.of("ok", true);
    }

    @PostMapping(
            value = "/video-offers",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VideoOfferResponse> createFromBitrix(
            @Valid @RequestBody BitrixCreateVideoOfferRequest request) {
        log.info("Bitrix video offer creation requested: recordingUrlPresent={}, accompanyingTextLength={}, viewNotificationGoal={}",
                request.recordingUrl() != null && !request.recordingUrl().isBlank(),
                request.accompanyingText() == null ? 0 : request.accompanyingText().length(),
                ru.abs7.videooffer.offer.ViewNotificationGoal.orDefault(request.viewNotificationGoal()));
        BitrixPlacementContext context = contextSigner.verify(request.contextToken());
        log.info("Bitrix signed context verified: memberId={}, entityType={}, entityId={}",
                context.memberId(), context.entityType(), context.entityId());
        VideoOffer offer = videoOfferService.create(new CreateVideoOfferRequest(
                context.entityType(),
                context.entityId(),
                context.memberId(),
                null,
                request.recordingUrl(),
                request.accompanyingText(),
                request.clientMessage(),
                request.viewNotificationGoal()));

        log.info("Bitrix video offer accepted: offerId={}, entityType={}, entityId={}, status={}",
                offer.getId(), offer.getCrmEntityType(), offer.getCrmEntityId(), offer.getStatus());
        return ResponseEntity.accepted().body(videoOfferService.response(offer));
    }

    private String safeLogValue(String value, int maxLength) {
        if (value == null) return "";
        String normalized = value.replaceAll("[\r\n\t]+", " ").trim();
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength) + "…";
    }

    public record BitrixClientEvent(String contextToken, String event, String details) { }

    private long extractEntityId(String placementOptions) {
        Matcher matcher = ID_PATTERN.matcher(placementOptions);
        if (!matcher.find()) {
            throw new IllegalArgumentException(
                    "Bitrix24 не передал ID карточки в PLACEMENT_OPTIONS");
        }
        long id = Long.parseLong(matcher.group(1));
        if (id <= 0) {
            throw new IllegalArgumentException("Bitrix24 передал некорректный ID карточки");
        }
        return id;
    }

    private String required(MultiValueMap<String, String> parameters, String name) {
        String value = parameters.getFirst(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Bitrix24 не передал обязательный параметр " + name);
        }
        return value.trim();
    }

    private String optional(MultiValueMap<String, String> parameters, String name) {
        String value = parameters.getFirst(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String escapeHtmlAttribute(String value) {
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
