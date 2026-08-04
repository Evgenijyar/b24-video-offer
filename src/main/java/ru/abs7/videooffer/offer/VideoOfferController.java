package ru.abs7.videooffer.offer;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(VideoOfferController.class);

    private final VideoOfferService service;

    public VideoOfferController(VideoOfferService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<VideoOfferResponse> create(
            @Valid @RequestBody CreateVideoOfferRequest request) {
        log.info("REST create video offer requested: entityType={}, entityId={}, bitrixMemberId={}",
                request.entityType(), request.entityId(), request.bitrixMemberId());
        VideoOffer offer = service.create(request);
        VideoOfferResponse response = service.response(offer);
        log.info("REST create video offer accepted: offerId={}, status={}, location=/api/video-offers/{}",
                offer.getId(), offer.getStatus(), offer.getId());
        return ResponseEntity
                .created(URI.create("/api/video-offers/" + offer.getId()))
                .body(response);
    }

    @GetMapping("/{id}")
    public VideoOfferResponse status(@PathVariable UUID id) {
        VideoOffer offer = service.get(id);
        log.debug("REST video offer status requested: offerId={}, status={}, progress={}%, bitrixDelivery={}",
                id,
                offer.getStatus(),
                offer.getProgressPercent(),
                offer.getBitrixDeliveryStatus());
        return service.response(offer);
    }

    @GetMapping
    public List<VideoOfferResponse> recent() {
        log.debug("REST recent video offers requested");
        return service.recent();
    }
}
