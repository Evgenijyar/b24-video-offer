package ru.abs7.videooffer.analytics;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/offers/{token}/events")
public class VideoOfferEventController {
    private final VideoOfferEventService service;

    public VideoOfferEventController(VideoOfferEventService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Void> record(
            @PathVariable String token,
            @Valid @RequestBody CreateVideoOfferEventRequest request,
            HttpServletRequest httpRequest) {
        service.record(token, request, httpRequest);
        return ResponseEntity.noContent().build();
    }
}
