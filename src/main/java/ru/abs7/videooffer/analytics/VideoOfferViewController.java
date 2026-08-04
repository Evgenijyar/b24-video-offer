package ru.abs7.videooffer.analytics;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/offers/{token}/view-progress")
public class VideoOfferViewController {
    private final VideoOfferViewTrackingService service;

    public VideoOfferViewController(VideoOfferViewTrackingService service) {
        this.service = service;
    }

    @PostMapping
    public VideoViewProgressResponse track(
            @PathVariable String token,
            @Valid @RequestBody TrackVideoProgressRequest request) {
        return service.track(token, request);
    }
}
