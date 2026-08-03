package ru.abs7.videooffer.offer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.abs7.videooffer.kontur.KonturRecordingUrlParser;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class VideoOfferService {
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
    }

    public VideoOffer create(CreateVideoOfferRequest request) {
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

        // Здесь намеренно нет внешней @Transactional-транзакции: saveAndFlush должен завершить
        // фиксацию записи до запуска фонового потока.
        VideoOffer saved = repository.saveAndFlush(offer);
        processor.process(saved.getId());
        return saved;
    }

    public VideoOffer get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Видеооффер не найден: " + id));
    }

    public VideoOffer getByToken(String token) {
        return repository.findByPublicToken(token)
                .orElseThrow(() -> new NoSuchElementException("Видеооффер не найден"));
    }

    public List<VideoOfferResponse> recent() {
        return repository.findTop20ByOrderByCreatedAtDesc().stream()
                .map(this::response)
                .toList();
    }

    public VideoOfferResponse response(VideoOffer offer) {
        return VideoOfferResponse.from(offer, publicBaseUrl);
    }

    public List<VideoOffer> findPendingForRecovery() {
        return repository.findAllByStatusIn(List.of(VideoOfferStatus.QUEUED, VideoOfferStatus.PREPARING));
    }

    public List<VideoOffer> findExpired() {
        return repository.findAllByExpiresAtBefore(OffsetDateTime.now());
    }

    public void delete(VideoOffer offer) {
        repository.delete(offer);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
