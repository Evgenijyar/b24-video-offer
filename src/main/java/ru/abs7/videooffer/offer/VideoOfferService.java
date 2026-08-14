package ru.abs7.videooffer.offer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.abs7.videooffer.bitrix.BitrixReadyLinkDeliveryService;
import ru.abs7.videooffer.kontur.KonturRecordingUrlParser;
import ru.abs7.videooffer.tenant.VideoOfferTenantRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private final Path videoStorageDir;
    private final BitrixReadyLinkDeliveryService bitrixReadyLinkDeliveryService;
    private final VideoOfferTenantRepository tenantRepository;

    public VideoOfferService(
            VideoOfferRepository repository,
            KonturRecordingUrlParser parser,
            VideoOfferProcessor processor,
            BitrixReadyLinkDeliveryService bitrixReadyLinkDeliveryService,
            VideoOfferTenantRepository tenantRepository,
            @Value("${app.public-base-url}") String publicBaseUrl,
            @Value("${app.video.retention-days:30}") int retentionDays,
            @Value("${app.video.storage-dir:./data/videos}") String videoStorageDir) {
        this.repository = repository;
        this.parser = parser;
        this.processor = processor;
        this.bitrixReadyLinkDeliveryService = bitrixReadyLinkDeliveryService;
        this.tenantRepository = tenantRepository;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.retentionDays = retentionDays;
        this.videoStorageDir = Path.of(videoStorageDir).toAbsolutePath().normalize();
        log.info("VideoOfferService initialized: publicBaseUrl={}, retentionDays={}, videoStorageDir={}",
                this.publicBaseUrl, retentionDays, this.videoStorageDir);
    }

    public VideoOffer create(CreateVideoOfferRequest request) {
        long startedAt = System.nanoTime();
        log.info("Creating video offer: entityType={}, entityId={}, bitrixMemberId={}, bitrixUserId={}, tenantId={}, "
                        + "recordingUrlPresent={}, accompanyingTextLength={}, clientMessageLength={}, viewNotificationGoal={}",
                request.entityType(),
                request.entityId(),
                normalize(request.bitrixMemberId()),
                request.bitrixUserId(),
                request.tenantId(),
                request.recordingUrl() != null && !request.recordingUrl().isBlank(),
                request.accompanyingText() == null ? 0 : request.accompanyingText().length(),
                request.clientMessage() == null ? 0 : request.clientMessage().length(),
                ViewNotificationGoal.orDefault(request.viewNotificationGoal()));

        String sourceUrl = request.recordingUrl().trim();
        String recordingKey = parser.isKonturRecordingUrl(sourceUrl)
                ? parser.extractRecordingKey(sourceUrl)
                : "external-" + UUID.randomUUID();
        VideoOffer offer = VideoOffer.create(
                request.entityType(),
                request.entityId(),
                normalize(request.bitrixMemberId()),
                request.bitrixUserId(),
                request.tenantId(),
                sourceUrl,
                recordingKey,
                normalize(request.accompanyingText()),
                normalize(request.clientMessage()),
                request.viewNotificationGoal(),
                retentionDaysForTenant(request.tenantId()));

        log.info("Video offer entity created in memory: offerId={}, publicToken={}, recordingKey={}, "
                        + "status={}, viewNotificationGoal={}, expiresAt={}",
                offer.getId(),
                offer.getPublicToken(),
                recordingKey,
                offer.getStatus(),
                offer.getViewNotificationGoal(),
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


    public VideoOffer createReadyFromMobile(
            UUID offerId,
            CrmEntityType entityType,
            long entityId,
            String memberId,
            Long bitrixUserId,
            Long tenantId,
            Path normalizedSource,
            String accompanyingText,
            String clientMessage,
            ViewNotificationGoal viewNotificationGoal,
            String quality) throws IOException {
        if (normalizedSource == null) {
            throw new IllegalArgumentException("Нормализованный мобильный видеофайл не найден");
        }

        UUID stableOfferId = offerId == null ? UUID.randomUUID() : offerId;
        VideoOffer saved = repository.findById(stableOfferId).orElse(null);
        if (saved != null && saved.getStatus() == VideoOfferStatus.READY) {
            return saved;
        }
        if (saved == null) {
            VideoOffer offer = VideoOffer.createWithId(
                    stableOfferId,
                    entityType,
                    entityId,
                    normalize(memberId),
                    bitrixUserId,
                    tenantId,
                    "mobile-upload://" + normalizedSource.getFileName(),
                    "mobile-" + UUID.randomUUID(),
                    normalize(accompanyingText),
                    normalize(clientMessage),
                    viewNotificationGoal,
                    retentionDaysForTenant(tenantId));
            saved = repository.saveAndFlush(offer);
        }

        Files.createDirectories(videoStorageDir);
        Path destination = videoStorageDir.resolve(stableOfferId + ".mp4");
        try {
            if (Files.isRegularFile(normalizedSource)) {
                if (!normalizedSource.toAbsolutePath().normalize().equals(destination.toAbsolutePath().normalize())) {
                    Files.move(normalizedSource, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            } else if (!Files.isRegularFile(destination)) {
                throw new IllegalArgumentException("Нормализованный мобильный видеофайл не найден");
            }
            long size = Files.size(destination);
            saved.markReady(destination.toString(), size, quality);
            saved = repository.saveAndFlush(saved);
            log.info("Mobile video offer READY: offerId={}, entityType={}, entityId={}, bytes={}, file={}",
                    saved.getId(), saved.getCrmEntityType(), saved.getCrmEntityId(), size, destination);
        } catch (IOException | RuntimeException error) {
            saved.markError(rootMessage(error));
            repository.saveAndFlush(saved);
            throw error;
        }

        return saved;
    }

    public VideoOffer get(UUID id) {
        log.debug("Loading video offer by id: offerId={}", id);
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Видеооффер не найден: " + id));
    }

    public VideoOffer findOrNull(UUID id) {
        return id == null ? null : repository.findById(id).orElse(null);
    }

    public void deliverReadyLinkAsync(UUID id) {
        bitrixReadyLinkDeliveryService.deliverAsync(id);
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

    private int retentionDaysForTenant(Long tenantId) {
        if (tenantId == null || tenantId <= 0) return retentionDays;
        return tenantRepository.findById(tenantId)
                .map(tenant -> tenant.getRetentionDays() == null ? retentionDays : tenant.getRetentionDays())
                .orElse(retentionDays);
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

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
