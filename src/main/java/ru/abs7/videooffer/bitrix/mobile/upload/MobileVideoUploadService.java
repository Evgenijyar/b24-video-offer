package ru.abs7.videooffer.bitrix.mobile.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.abs7.videooffer.bitrix.BitrixContextSigner;
import ru.abs7.videooffer.bitrix.BitrixPlacementContext;
import ru.abs7.videooffer.offer.VideoOffer;
import ru.abs7.videooffer.offer.VideoOfferResponse;
import ru.abs7.videooffer.offer.VideoOfferService;
import ru.abs7.videooffer.offer.ViewNotificationGoal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class MobileVideoUploadService {
    private static final Logger log = LoggerFactory.getLogger(MobileVideoUploadService.class);
    private static final int BUFFER_SIZE = 64 * 1024;

    private final MobileVideoUploadRepository repository;
    private final BitrixContextSigner contextSigner;
    private final MobileVideoUploadProcessor processor;
    private final VideoOfferService videoOfferService;
    private final TransactionTemplate transactionTemplate;
    private final Path uploadDirectory;
    private final long maxUploadBytes;
    private final long maxChunkBytes;
    private final int retentionHours;

    public MobileVideoUploadService(
            MobileVideoUploadRepository repository,
            BitrixContextSigner contextSigner,
            MobileVideoUploadProcessor processor,
            VideoOfferService videoOfferService,
            PlatformTransactionManager transactionManager,
            @Value("${app.video.storage-dir:./data/videos}") String videoStorageDir,
            @Value("${app.mobile-video.max-upload-bytes:536870912}") long maxUploadBytes,
            @Value("${app.mobile-video.max-chunk-bytes:16777216}") long maxChunkBytes,
            @Value("${app.mobile-video.retention-hours:24}") int retentionHours) throws IOException {
        this.repository = repository;
        this.contextSigner = contextSigner;
        this.processor = processor;
        this.videoOfferService = videoOfferService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        Path videos = Path.of(videoStorageDir).toAbsolutePath().normalize();
        Path dataDir = videos.getParent() == null ? videos : videos.getParent();
        this.uploadDirectory = dataDir.resolve("mobile-uploads");
        Files.createDirectories(this.uploadDirectory);
        this.maxUploadBytes = Math.max(32L * 1024 * 1024, maxUploadBytes);
        this.maxChunkBytes = Math.max(1L * 1024 * 1024, maxChunkBytes);
        this.retentionHours = Math.max(1, retentionHours);
        log.info("MobileVideoUploadService initialized: uploadDirectory={}, maxUploadBytes={}, maxChunkBytes={}, retentionHours={}",
                uploadDirectory, this.maxUploadBytes, this.maxChunkBytes, this.retentionHours);
    }

    public MobileVideoUploadResponse create(CreateMobileVideoUploadRequest request) {
        BitrixPlacementContext context = contextSigner.verify(request.contextToken());
        String mimeType = normalizeMimeType(request.mimeType());

        MobileVideoUpload upload = MobileVideoUpload.create(
                context.memberId(),
                context.entityType(),
                context.entityId(),
                mimeType,
                uploadDirectory.toString(),
                retentionHours);

        MobileVideoUpload saved = repository.saveAndFlush(upload);
        log.info("Mobile video upload session created: uploadId={}, entityType={}, entityId={}, mimeType={}, expiresAt={}",
                saved.getId(), saved.getCrmEntityType(), saved.getCrmEntityId(), saved.getMimeType(), saved.getExpiresAt());
        return MobileVideoUploadResponse.from(saved);
    }

    public MobileVideoUploadResponse appendChunk(
            UUID uploadId,
            String uploadToken,
            int sequence,
            InputStream inputStream) throws IOException {
        if (sequence < 0) {
            throw new IllegalArgumentException("Некорректный номер части видео");
        }

        MobileVideoUpload snapshot = requireAuthorized(uploadId, uploadToken);
        if (snapshot.alreadyAccepted(sequence)) {
            log.info("Duplicate mobile upload chunk ignored: uploadId={}, sequence={}, nextSequence={}",
                    uploadId, sequence, snapshot.getNextSequence());
            return MobileVideoUploadResponse.from(snapshot);
        }
        if (sequence != snapshot.getNextSequence()) {
            throw new IllegalArgumentException(
                    "Нарушен порядок загрузки: ожидается часть " + snapshot.getNextSequence());
        }

        Path temporaryChunk = uploadDirectory.resolve(uploadId + "." + sequence + "." + UUID.randomUUID() + ".chunk");
        long chunkBytes;
        try {
            chunkBytes = copyLimited(inputStream, temporaryChunk, maxChunkBytes);
            if (chunkBytes <= 0) {
                throw new IllegalArgumentException("Получена пустая часть видео");
            }
        } catch (IOException | RuntimeException error) {
            Files.deleteIfExists(temporaryChunk);
            throw error;
        }

        try {
            MobileVideoUpload updated = transactionTemplate.execute(status -> {
                MobileVideoUpload upload = repository.findByIdForUpdate(uploadId)
                        .orElseThrow(() -> new NoSuchElementException("Сессия записи видео не найдена"));
                verifyToken(upload, uploadToken);
                if (upload.alreadyAccepted(sequence)) {
                    return upload;
                }
                if (sequence != upload.getNextSequence()) {
                    throw new IllegalArgumentException(
                            "Нарушен порядок загрузки: ожидается часть " + upload.getNextSequence());
                }
                if (upload.getBytesReceived() + chunkBytes > maxUploadBytes) {
                    throw new IllegalArgumentException("Видео слишком большое. Максимальный размер — "
                            + Math.round(maxUploadBytes / 1024.0 / 1024.0) + " МБ");
                }

                Path source = Path.of(upload.getSourceFilePath());
                try {
                    Files.createDirectories(source.toAbsolutePath().normalize().getParent());
                    try (OutputStream output = Files.newOutputStream(
                            source,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND)) {
                        Files.copy(temporaryChunk, output);
                    }
                } catch (IOException ioError) {
                    throw new IllegalStateException("Не удалось сохранить часть видео", ioError);
                }
                upload.acceptChunk(sequence, chunkBytes);
                return repository.saveAndFlush(upload);
            });
            log.info("Mobile video chunk accepted: uploadId={}, sequence={}, chunkBytes={}, totalBytes={}",
                    uploadId, sequence, chunkBytes, updated.getBytesReceived());
            return MobileVideoUploadResponse.from(updated);
        } finally {
            Files.deleteIfExists(temporaryChunk);
        }
    }

    public MobileVideoUploadResponse complete(UUID uploadId, String uploadToken) {
        MobileVideoUpload updated = transactionTemplate.execute(status -> {
            MobileVideoUpload upload = repository.findByIdForUpdate(uploadId)
                    .orElseThrow(() -> new NoSuchElementException("Сессия записи видео не найдена"));
            verifyToken(upload, uploadToken);
            upload.markUploaded();
            return repository.saveAndFlush(upload);
        });
        if (updated.getStatus() == MobileVideoUploadStatus.UPLOADED) {
            processor.normalize(updated.getId());
        }
        log.info("Mobile video upload completed by client: uploadId={}, bytes={}, status={}",
                uploadId, updated.getBytesReceived(), updated.getStatus());
        return MobileVideoUploadResponse.from(updated);
    }

    public MobileVideoUploadResponse status(UUID uploadId, String uploadToken) {
        return MobileVideoUploadResponse.from(requireAuthorized(uploadId, uploadToken));
    }

    public void discard(UUID uploadId, String uploadToken) throws IOException {
        MobileVideoUpload discarded = transactionTemplate.execute(status -> {
            MobileVideoUpload upload = repository.findByIdForUpdate(uploadId)
                    .orElseThrow(() -> new NoSuchElementException("Сессия записи видео не найдена"));
            verifyToken(upload, uploadToken);
            if (upload.getStatus() == MobileVideoUploadStatus.CONSUMED) {
                throw new IllegalArgumentException("Готовый видеооффер уже создан и не может быть удалён этой операцией");
            }
            repository.delete(upload);
            repository.flush();
            return upload;
        });

        deleteIfPresent(discarded.getSourceFilePath());
        deleteIfPresent(discarded.getNormalizedFilePath());
        log.info("Mobile video upload discarded by client: uploadId={}, status={}, bytes={}",
                discarded.getId(), discarded.getStatus(), discarded.getBytesReceived());
    }

    public VideoOfferResponse createOffer(
            UUID uploadId,
            CreateMobileVideoOfferRequest request) throws IOException {
        MobileVideoUpload upload = requireAuthorized(uploadId, request.uploadToken());
        if (upload.getStatus() == MobileVideoUploadStatus.CONSUMED && upload.getVideoOfferId() != null) {
            return videoOfferService.response(videoOfferService.get(upload.getVideoOfferId()));
        }
        if (upload.getStatus() == MobileVideoUploadStatus.ERROR) {
            throw new IllegalArgumentException("Видео не удалось обработать: " + upload.getErrorMessage());
        }
        if (upload.getStatus() != MobileVideoUploadStatus.READY || upload.getNormalizedFilePath() == null) {
            throw new IllegalArgumentException("Видео ещё не готово. Дождитесь окончания обработки");
        }

        VideoOffer offer = videoOfferService.createReadyFromMobile(
                upload.getCrmEntityType(),
                upload.getCrmEntityId(),
                upload.getBitrixMemberId(),
                Path.of(upload.getNormalizedFilePath()),
                request.accompanyingText(),
                ViewNotificationGoal.orDefault(request.viewNotificationGoal()),
                "mobile-720p-h264");

        MobileVideoUpload consumed = transactionTemplate.execute(status -> {
            MobileVideoUpload current = repository.findByIdForUpdate(uploadId)
                    .orElseThrow(() -> new NoSuchElementException("Сессия записи видео не найдена"));
            verifyToken(current, request.uploadToken());
            if (current.getStatus() != MobileVideoUploadStatus.CONSUMED) {
                current.markConsumed(offer.getId());
                repository.saveAndFlush(current);
            }
            return current;
        });
        log.info("Mobile video upload consumed by video offer: uploadId={}, offerId={}, entityType={}, entityId={}",
                consumed.getId(), offer.getId(), offer.getCrmEntityType(), offer.getCrmEntityId());
        return videoOfferService.response(offer);
    }

    public Path previewFile(UUID uploadId, String uploadToken) {
        MobileVideoUpload upload = requireAuthorized(uploadId, uploadToken);
        if (upload.getStatus() != MobileVideoUploadStatus.READY
                && upload.getStatus() != MobileVideoUploadStatus.CONSUMED) {
            throw new IllegalArgumentException("Видео ещё не готово для предпросмотра");
        }
        String path = upload.getNormalizedFilePath();
        if (path == null || path.isBlank() || !Files.isRegularFile(Path.of(path))) {
            // After consumption the normalized path is moved into the final offer file.
            if (upload.getVideoOfferId() != null) {
                VideoOffer offer = videoOfferService.get(upload.getVideoOfferId());
                path = offer.getVideoFilePath();
            }
        }
        if (path == null || path.isBlank() || !Files.isRegularFile(Path.of(path))) {
            throw new NoSuchElementException("Файл предпросмотра не найден");
        }
        return Path.of(path);
    }

    public void cleanupExpired() {
        for (MobileVideoUpload upload : repository.findAllByExpiresAtBefore(OffsetDateTime.now())) {
            try {
                deleteIfPresent(upload.getSourceFilePath());
                if (upload.getStatus() != MobileVideoUploadStatus.CONSUMED) {
                    deleteIfPresent(upload.getNormalizedFilePath());
                }
                repository.delete(upload);
                log.info("Expired mobile video upload removed: uploadId={}, status={}",
                        upload.getId(), upload.getStatus());
            } catch (Exception error) {
                log.warn("Cannot clean expired mobile video upload: uploadId={}, error={}",
                        upload.getId(), error.getMessage(), error);
            }
        }
    }

    private MobileVideoUpload requireAuthorized(UUID uploadId, String uploadToken) {
        MobileVideoUpload upload = repository.findById(uploadId)
                .orElseThrow(() -> new NoSuchElementException("Сессия записи видео не найдена"));
        verifyToken(upload, uploadToken);
        return upload;
    }

    private void verifyToken(MobileVideoUpload upload, String uploadToken) {
        if (uploadToken == null || uploadToken.isBlank()
                || !upload.getUploadToken().equals(uploadToken.trim())) {
            throw new IllegalArgumentException("Недействительный токен загрузки видео");
        }
    }

    private String normalizeMimeType(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if (normalized.isBlank()) {
            normalized = "application/octet-stream";
        }
        if (!normalized.startsWith("video/") && !"application/octet-stream".equals(normalized)) {
            throw new IllegalArgumentException("Неподдерживаемый тип файла: " + value);
        }
        if (normalized.length() > 160) {
            throw new IllegalArgumentException("Слишком длинное описание формата видео");
        }
        return normalized;
    }

    private long copyLimited(InputStream source, Path target, long limit) throws IOException {
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        long total = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (OutputStream output = Files.newOutputStream(
                target,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            int read;
            while ((read = source.read(buffer)) >= 0) {
                total += read;
                if (total > limit) {
                    throw new IllegalArgumentException("Одна часть видео превышает допустимый размер");
                }
                output.write(buffer, 0, read);
            }
        }
        return total;
    }

    private void deleteIfPresent(String path) throws IOException {
        if (path != null && !path.isBlank()) {
            Files.deleteIfExists(Path.of(path));
        }
    }
}
