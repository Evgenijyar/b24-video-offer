package ru.abs7.videooffer.analytics;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.abs7.videooffer.offer.VideoOffer;
import ru.abs7.videooffer.offer.VideoOfferService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class VideoOfferEventService {
    private static final Logger log = LoggerFactory.getLogger(VideoOfferEventService.class);
    private final VideoOfferService videoOfferService;
    private final VideoOfferEventRepository repository;

    public VideoOfferEventService(
            VideoOfferService videoOfferService,
            VideoOfferEventRepository repository) {
        this.videoOfferService = videoOfferService;
        this.repository = repository;
    }

    public void record(String token, CreateVideoOfferEventRequest request, HttpServletRequest httpRequest) {
        VideoOffer offer = videoOfferService.getByToken(token);
        String userAgent = truncate(httpRequest.getHeader("User-Agent"), 2000);
        String remoteAddress = resolveRemoteAddress(httpRequest);
        log.info("Recording public video event: offerId={}, eventType={}, positionSeconds={}, userAgentPresent={}",
                offer.getId(), request.eventType(), request.playbackPositionSeconds(), userAgent != null);
        repository.save(VideoOfferEvent.create(
                offer,
                request.eventType(),
                request.playbackPositionSeconds(),
                userAgent,
                hash(remoteAddress)));
        log.info("Public video event persisted: offerId={}, eventType={}", offer.getId(), request.eventType());
    }

    private String resolveRemoteAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",", 2)[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String hash(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
