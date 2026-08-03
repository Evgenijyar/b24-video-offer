package ru.abs7.videooffer.offer;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/video-offers")
public class VideoOfferController {
    private final VideoOfferService service;

    public VideoOfferController(VideoOfferService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<VideoOfferResponse> create(@Valid @RequestBody CreateVideoOfferRequest request) {
        VideoOffer offer = service.create(request);
        return ResponseEntity
                .created(URI.create("/api/video-offers/" + offer.getId()))
                .body(service.response(offer));
    }

    @GetMapping("/{id}")
    public VideoOfferResponse status(@PathVariable UUID id) {
        return service.response(service.get(id));
    }

    @GetMapping
    public List<VideoOfferResponse> recent() {
        return service.recent();
    }
}
