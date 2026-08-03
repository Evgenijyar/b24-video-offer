package ru.abs7.videooffer.offer;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/video-offers")
public class VideoOfferController {
    private final VideoOfferService service;
    public VideoOfferController(VideoOfferService service){this.service=service;}
    @PostMapping public ResponseEntity<VideoOfferResponse> create(@Valid @RequestBody CreateVideoOfferRequest request){
        VideoOffer offer=service.create(request);
        return ResponseEntity.created(URI.create("/api/video-offers/"+offer.getId())).body(service.response(offer));
    }
    @GetMapping("/{id}") public VideoOfferResponse status(@PathVariable UUID id){return service.response(service.get(id));}
}
