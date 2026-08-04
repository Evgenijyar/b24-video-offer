package ru.abs7.videooffer.offer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.abs7.videooffer.kontur.KonturRecordingUrlParser;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class VideoOfferService {
    private static final Logger log = LoggerFactory.getLogger(VideoOfferService.class);

    private final VideoOfferRepository repository;
    private final KonturRecordingUrlParser parser;
    private final VideoOfferProcessor processor;
    private final String publicBaseUrl;
    private final int retentionDays;

    public VideoOfferService(
            VideoOfferRepository repository,
            KonturRecordingUrlParser parser,
            VideoOfferProcessor processor,
            @Value("${app.public-base-url}") String publicBaseUrl,
            @Value("${app.video.retention-days:30}") int retentionDays) {
        this.repository = repository;
        this.parser = parser;
        this.processor = processor;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.retentionDays = retentionDays;
        log.info("VideoOfferService initialized: publicBaseUrl={}, retentionDays={}",
                this.publicBaseUrl, retentionDays);
    }

    public VideoOffer create(CreateVideoOfferRequest request) {
        long startedAt = System.nanoTime();
        log.info("Creating video offer: entityType={}, entityId={}, bitrixMemberId={}, bitrixUserId={}, "
                        + "recordingUrlPresent={}, accompanyingTextLength={}",
                request.entityType(),
                request.entityId(),
                normalize(request.bitrixMemberId()),
                request.bitrixUserId(),
                request.recordingUrl() != null && !request.recordingUrl().isBlank(),
                request.accompanyingText() == null ? 0 : request.accompanyingText().length());

        String recordingKey = parser.extractRecordingKey(request.recordingUrl());
        VideoOffer offer = VideoOffer.create(
                request.entityType(),
                request.entityId(),
                normalize(request.bitrixMemberId()),
                request.bitrixUserId(),
                request.recordingUrl().trim(),
                recordingKey,
                normalize(request.accompanyingText()),
                retentionDays);

        log.info("Video offer entity created in memory: offerId={}, publicToken={}, recordingKey={}, "
                        + "status={}, expiresAt={}",
                offer.getId(),
                offer.getPublicToken(),
                recordingKey,
                offer.getStatus(),
                offer.getExpiresAt());

        // Здесь намеренно нет внешней @Transactional-транзакции: saveAndFlush должен завершить
        // фиксацию записи до запуска фонового потока.
        VideoOffer saved = repository.saveAndFlush(offer);
        log.info("Video offer persisted: offerId={}, status={}, progress={}%, durationMs={}",
                saved.getId(),
                saved.getStatus(),
                saved.getProgressPercent(),
                elapsedMillis(startedAt));

        processor.process(saved.getId());
        log.info("Video offer background processing submitted: offerId={}", saved.getId());
        return saved;
    }

    public VideoOffer get(UUID id) {
        log.debug("Loading video offer by id: offerId={}", id);
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Видеооффер не найден: " + id));
    }

    public VideoOffer getByToken(String token) {
        log.debug("Loading video offer by public token: tokenPrefix={}", tokenPrefix(token));
        return repository.findByPublicToken(token)
                .orElseThrow(() -> new NoSuchElementException("Видеооффер не найден"));
    }

    public List<VideoOfferResponse> recent() {
        List<VideoOfferResponse> result = repository.findTop20ByOrderByCreatedAtDesc().stream()
                .map(this::response)
                .toList();
        log.debug("Loaded recent video offers: count={}", result.size());
        return result;
    }

    public VideoOfferResponse response(VideoOffer offer) {
        return VideoOfferResponse.from(offer, publicBaseUrl);
    }

    public List<VideoOffer> findPendingForRecovery() {
        List<VideoOffer> pending = repository.findAllByStatusIn(
                List.of(VideoOfferStatus.QUEUED, VideoOfferStatus.PREPARING));
        log.info("Pending video offers selected for recovery: count={}", pending.size());
        return pending;
    }

    public List<VideoOffer> findExpired() {
        List<VideoOffer> expired = repository.findAllByExpiresAtBefore(OffsetDateTime.now());
        log.info("Expired video offers selected for cleanup: count={}", expired.size());
        return expired;
    }

    public void delete(VideoOffer offer) {
        log.info("Deleting video offer from database: offerId={}, status={}, file={}",
                offer.getId(), offer.getStatus(), offer.getVideoFilePath());
        repository.delete(offer);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String tokenPrefix(String token) {
        if (token == null) {
            return "null";
        }
        return token.length() <= 8 ? token : token.substring(0, 8) + "...";
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
