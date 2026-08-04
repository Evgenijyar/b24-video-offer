package ru.abs7.videooffer.bitrix;

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
    private final String widgetTemplate;

    public BitrixController(
            BitrixInstallationService installationService,
            BitrixContextSigner contextSigner,
            VideoOfferService videoOfferService) throws IOException {
        this.installationService = installationService;
        this.contextSigner = contextSigner;
        this.videoOfferService = videoOfferService;
        this.widgetTemplate = new ClassPathResource("static/bitrix-widget.html")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @PostMapping(
            value = "/install",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public BitrixInstallationService.InstallationResult install(
            @RequestParam MultiValueMap<String, String> parameters) {
        log.info("Bitrix installation callback received: parameterNames={}", parameters.keySet());
        BitrixInstallationService.InstallationResult result = installationService.install(parameters);
        log.info("Bitrix installation callback completed: domain={}, memberId={}, placements={}",
                result.domain(), result.memberId(), result.placements());
        return result;
    }

    @GetMapping(value = "/install", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> installInfo() {
        log.info("Bitrix installation endpoint opened with GET");
        return Map.of(
                "status", "READY",
                "message", "Этот URL должен вызываться Bitrix24 методом POST при установке приложения");
    }

    @PostMapping(
            value = "/widget",
            produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> widget(
            @RequestParam MultiValueMap<String, String> parameters) {
        log.info("Bitrix widget callback received: parameterNames={}", parameters.keySet());
        installationService.synchronizeAuthorizationFromWidget(parameters);
        String placement = required(parameters, "PLACEMENT");
        String memberId = required(parameters, "member_id");
        String placementOptions = required(parameters, "PLACEMENT_OPTIONS");

        CrmEntityType entityType = CrmEntityType.fromBitrixPlacement(placement);
        long entityId = extractEntityId(placementOptions);
        log.info("Bitrix widget context resolved: placement={}, memberId={}, entityType={}, entityId={}",
                placement, memberId, entityType, entityId);
        String contextToken = contextSigner.create(
                new BitrixPlacementContext(memberId, entityType, entityId));

        String html = widgetTemplate
                .replace("{{CONTEXT_TOKEN}}", escapeHtmlAttribute(contextToken))
                .replace("{{ENTITY_TYPE}}", entityType.name())
                .replace("{{ENTITY_LABEL}}", entityType.russianLabel())
                .replace("{{ENTITY_ID}}", Long.toString(entityId));

        log.info("Bitrix widget HTML generated: entityType={}, entityId={}, htmlBytes={}",
                entityType, entityId, html.getBytes(StandardCharsets.UTF_8).length);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
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
                request.viewNotificationGoal()));

        log.info("Bitrix video offer accepted: offerId={}, entityType={}, entityId={}, status={}",
                offer.getId(), offer.getCrmEntityType(), offer.getCrmEntityId(), offer.getStatus());
        return ResponseEntity.accepted().body(videoOfferService.response(offer));
    }

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

    private String escapeHtmlAttribute(String value) {
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
