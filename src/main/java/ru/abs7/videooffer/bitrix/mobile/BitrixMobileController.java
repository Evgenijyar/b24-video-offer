package ru.abs7.videooffer.bitrix.mobile;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.abs7.videooffer.bitrix.BitrixInstallationService;
import ru.abs7.videooffer.offer.CrmEntityType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/bitrix/mobile")
public class BitrixMobileController {
    private static final Logger log = LoggerFactory.getLogger(BitrixMobileController.class);

    private final BitrixInstallationService installationService;
    private final BitrixMobileContextSigner mobileContextSigner;
    private final BitrixCrmSearchService searchService;
    private final String mobileTemplate;

    public BitrixMobileController(
            BitrixInstallationService installationService,
            BitrixMobileContextSigner mobileContextSigner,
            BitrixCrmSearchService searchService) throws IOException {
        this.installationService = installationService;
        this.mobileContextSigner = mobileContextSigner;
        this.searchService = searchService;
        this.mobileTemplate = new ClassPathResource("static/bitrix-mobile.html")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @PostMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> openMobileApplication(
            @RequestParam MultiValueMap<String, String> parameters,
            HttpServletRequest servletRequest) {
        log.info("Bitrix mobile application opened: parameterNames={}, userAgent={}",
                parameters.keySet(), servletRequest.getHeader("User-Agent"));
        installationService.synchronizeAuthorizationFromWidget(parameters);
        String memberId = required(parameters, "member_id", "auth[member_id]", "MEMBER_ID");
        String token = mobileContextSigner.create(memberId);
        String html = mobileTemplate.replace("{{MOBILE_CONTEXT_TOKEN}}", escapeHtmlAttribute(token));
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> mobileApplicationInfo() {
        String html = """
                <!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Видео-оффер</title></head>
                <body style=\"font-family:Arial,sans-serif;padding:24px\"><h1>Видео-оффер</h1><p>Откройте приложение из мобильного Bitrix24. При прямом открытии сервер не получает авторизационный контекст портала.</p></body></html>
                """;
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public BitrixCrmSearchResponse search(
            @RequestParam String mobileContextToken,
            @RequestParam CrmEntityType entityType,
            @RequestParam("q") String query) {
        String memberId = mobileContextSigner.verify(mobileContextToken);
        List<BitrixCrmSearchResult> results = searchService.search(memberId, entityType, query);
        return new BitrixCrmSearchResponse(entityType, query.trim(), results);
    }

    private String required(
            MultiValueMap<String, String> parameters,
            String... names) {
        for (String name : names) {
            String value = parameters.getFirst(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        throw new IllegalArgumentException("Bitrix24 не передал обязательный параметр " + names[0]);
    }

    private String escapeHtmlAttribute(String value) {
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
