package ru.abs7.videooffer.bitrix;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
public class BitrixScreenCaptureController {
    private final String template;

    public BitrixScreenCaptureController() throws IOException {
        this.template = new ClassPathResource("static/bitrix-screen-capture.html")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @GetMapping(value = "/bitrix/screen-capture", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> screenCapture() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("Permissions-Policy", "display-capture=(self), microphone=(self)")
                .header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .body(template);
    }
}
