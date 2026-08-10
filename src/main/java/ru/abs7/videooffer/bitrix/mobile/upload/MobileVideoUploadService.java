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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class MobileVideoUploadService {
    private static final Logger log = LoggerFactory.getLogger(MobileVideoUploadService.class);
    private static final int BUFFER_SIZE = 64 * 1024;

    private final MobileVideoUploadRepository repository;
    private final BitrixContextSigner contextSigner;
    private final MobileVideoUploadProcessor processor;
    private final MobileVideoMerger merger;
    private final VideoOfferService videoOfferService;
    private final TransactionTemplate transactionTemplate;
    private final Path uploadDirectory;
    private final long maxUploadBytes;
    private final long maxChunkBytes;
    private final long maxManualFileBytes;
    private final int retentionHours;
    private final ConcurrentHashMap<UUID, ReentrantLock> uploadFileLocks = new ConcurrentHashMap<>();

    public MobileVideoUploadService(
            MobileVideoUploadRepository repository,
            BitrixContextSigner contextSigner,
            MobileVideoUploadProcessor processor,
            MobileVideoMerger merger,
            VideoOfferService videoOfferService,
            PlatformTransactionManager transactionManager,
            @Value("${app.video.storage-dir:./data/videos}") String videoStorageDir,
            @Value("${app.mobile-video.max-upload-bytes:536870912}") long maxUploadBytes,
            @Value("${app.mobile-video.max-chunk-bytes:16777216}") long maxChunkBytes,
            @Value("${app.video.manual-upload-max-bytes:104857600}") long maxManualFileBytes,
            @Value("${app.mobile-video.retention-hours:24}") int retentionHours) throws IOException {
        this.repository = repository;
        this.contextSigner = contextSigner;
        this.processor = processor;
        this.merger = merger;
        this.videoOfferService = videoOfferService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        Path videos = Path.of(videoStorageDir).toAbsolutePath().normalize();
        Path dataDir = videos.getParent() == null ? videos : videos.getParent();
        this.uploadDirectory = dataDir.resolve("mobile-uploads");
        Files.createDirectories(this.uploadDirectory);
        this.maxUploadBytes = Math.max(32L * 1024 * 1024, maxUploadBytes);
        this.maxChunkBytes = Math.max(1L * 1024 * 1024, maxChunkBytes);
        this.maxManualFileBytes = Math.max(1L * 1024 * 1024, maxManualFileBytes);
        this.retentionHours = Math.max(1, retentionHours);
        log.info("MobileVideoUploadService initialized: uploadDirectory={}, maxUploadBytes={}, maxChunkBytes={}, maxManualFileBytes={}, retentionHours={}",
                uploadDirectory, this.maxUploadBytes, this.maxChunkBytes, this.maxManualFileBytes, this.retentionHours);
    }

    public MobileVideoUploadResponse create(CreateMobileVideoUploadRequest request) {
        BitrixPlacementContext context = contextSigner.verify(request.contextToken());
        MobileVideoSourceKind sourceKind = MobileVideoSourceKind.orDefault(request.sourceKind());
        String mimeType = normalizeMimeType(request.mimeType(), sourceKind);
        Long declaredSizeBytes = request.declaredSizeBytes();
        if (sourceKind == MobileVideoSourceKind.FILE) {
            if (declaredSizeBytes == null || declaredSizeBytes <= 0) {
                throw new IllegalArgumentException("Не удалось определить размер выбранного видеофайла");
            }
            if (declaredSizeBytes > maxManualFileBytes) {
                throw new IllegalArgumentException("Видео слишком большое. Для загрузки файла максимальный размер — "
                        + Math.round(maxManualFileBytes / 1024.0 / 1024.0) + " МБ");
            }
        }

        MobileVideoUpload upload = MobileVideoUpload.create(
                context.memberId(),
                context.entityType(),
                context.entityId(),
                mimeType,
                sourceKind,
                declaredSizeBytes,
                uploadDirectory.toString(),
                retentionHours);

        MobileVideoUpload saved = repository.saveAndFlush(upload);
        log.info("Mobile video upload session created: uploadId={}, entityType={}, entityId={}, sourceKind={}, mimeType={}, declaredSizeBytes={}, expiresAt={}",
                saved.getId(), saved.getCrmEntityType(), saved.getCrmEntityId(), saved.getSourceKind(), saved.getMimeType(),
                saved.getDeclaredSizeBytes(), saved.getExpiresAt());
        return MobileVideoUploadResponse.from(saved);
    }

    public MobileVideoUploadResponse appendChunk(
            UUID uploadId,
            String uploadToken,
            int sequence,
            InputStream inputStream) throws IOException {
        if (sequence < 0 || sequence > 100_000) {
            throw new IllegalArgumentException("Некорректный номер части видео");
        }

        MobileVideoUpload snapshot = requireAuthorized(uploadId, uploadToken);
        if (snapshot.getStatus() != MobileVideoUploadStatus.RECORDING) {
            Path existingPart = partPath(uploadId, sequence);
            if (Files.isRegularFile(existingPart)) {
                return MobileVideoUploadResponse.from(snapshot);
            }
            throw new IllegalArgumentException("Запись уже завершена и больше не принимает данные");
        }

        Path temporaryChunk = uploadDirectory.resolve(
                uploadId + ".part." + formatSequence(sequence) + ".tmp." + UUID.randomUUID());
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

        ReentrantLock fileLock = lockFor(uploadId);
        fileLock.lock();
        try {
            MobileVideoUpload currentSnapshot = requireAuthorized(uploadId, uploadToken);
            if (currentSnapshot.getStatus() != MobileVideoUploadStatus.RECORDING) {
                return MobileVideoUploadResponse.from(currentSnapshot);
            }
            Path part = partPath(uploadId, sequence);
            boolean newlyStored = false;
            if (Files.isRegularFile(part)) {
                long existingBytes = Files.size(part);
                if (existingBytes != chunkBytes) {
                    throw new IllegalArgumentException(
                            "Повторная часть видео имеет другой размер: sequence=" + sequence);
                }
                log.debug("Duplicate mobile upload chunk accepted idempotently: uploadId={}, sequence={}, bytes={}",
                        uploadId, sequence, existingBytes);
            } else {
                moveWithoutReplacing(temporaryChunk, part);
                newlyStored = true;
            }

            long totalBytes = calculateStoredPartBytes(uploadId);
            long uploadLimit = maxAllowedBytes(currentSnapshot);
            if (totalBytes > uploadLimit) {
                if (newlyStored) {
                    Files.deleteIfExists(part);
                }
                throw new IllegalArgumentException("Видео слишком большое. Максимальный размер — "
                        + Math.round(uploadLimit / 1024.0 / 1024.0) + " МБ");
            }

            MobileVideoUpload updated = transactionTemplate.execute(status -> {
                MobileVideoUpload upload = repository.findByIdForUpdate(uploadId)
                        .orElseThrow(() -> new NoSuchElementException("Сессия записи видео не найдена"));
                verifyToken(upload, uploadToken);
                if (upload.getStatus() != MobileVideoUploadStatus.RECORDING) {
                    return upload;
                }
                upload.acceptChunk(sequence, totalBytes);
                return repository.saveAndFlush(upload);
            });

            log.info("Mobile video chunk accepted: uploadId={}, sequence={}, chunkBytes={}, totalBytes={}, parallelSafe=true",
                    uploadId, sequence, chunkBytes, totalBytes);
            return MobileVideoUploadResponse.from(updated);
        } finally {
            Files.deleteIfExists(temporaryChunk);
            fileLock.unlock();
        }
    }

    public MobileVideoUploadResponse complete(UUID uploadId, String uploadToken, int chunkCount) throws IOException {
        if (chunkCount <= 0 || chunkCount > 100_000) {
            throw new IllegalArgumentException("Некорректное количество частей видео");
        }

        ReentrantLock fileLock = lockFor(uploadId);
        fileLock.lock();
        MobileVideoUpload updated;
        boolean startProcessing = false;
        long assemblyStartedAt = System.nanoTime();
        try {
            MobileVideoUpload snapshot = requireAuthorized(uploadId, uploadToken);
            if (snapshot.getStatus() == MobileVideoUploadStatus.UPLOADED
                    || snapshot.getStatus() == MobileVideoUploadStatus.PROCESSING
                    || snapshot.getStatus() == MobileVideoUploadStatus.READY
                    || snapshot.getStatus() == MobileVideoUploadStatus.CONSUMED) {
                return MobileVideoUploadResponse.from(snapshot);
            }
            if (snapshot.getStatus() != MobileVideoUploadStatus.RECORDING) {
                throw new IllegalArgumentException("Эту запись нельзя завершить в статусе " + snapshot.getStatus());
            }

            Path source = Path.of(snapshot.getSourceFilePath());
            Path assembly = source.resolveSibling(uploadId + ".assembling");
            Files.createDirectories(source.toAbsolutePath().normalize().getParent());
            Files.deleteIfExists(assembly);

            long totalBytes = 0;
            try (OutputStream output = Files.newOutputStream(
                    assembly, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                for (int sequence = 0; sequence < chunkCount; sequence++) {
                    Path part = partPath(uploadId, sequence);
                    if (!Files.isRegularFile(part)) {
                        throw new IllegalArgumentException(
                                "Не все части видео успели загрузиться. Отсутствует часть " + sequence
                                        + " из " + chunkCount);
                    }
                    long partBytes = Files.size(part);
                    totalBytes += partBytes;
                    long uploadLimit = maxAllowedBytes(snapshot);
                    if (totalBytes > uploadLimit) {
                        throw new IllegalArgumentException("Видео слишком большое. Максимальный размер — "
                                + Math.round(uploadLimit / 1024.0 / 1024.0) + " МБ");
                    }
                    Files.copy(part, output);
                }
            } catch (IOException | RuntimeException error) {
                Files.deleteIfExists(assembly);
                throw error;
            }

            moveReplacing(assembly, source);
            long assembledBytes = totalBytes;
            updated = transactionTemplate.execute(status -> {
                MobileVideoUpload upload = repository.findByIdForUpdate(uploadId)
                        .orElseThrow(() -> new NoSuchElementException("Сессия записи видео не найдена"));
                verifyToken(upload, uploadToken);
                upload.markUploaded(assembledBytes, chunkCount);
                return repository.saveAndFlush(upload);
            });
            deletePartFiles(uploadId);
            startProcessing = updated.getStatus() == MobileVideoUploadStatus.UPLOADED;
            log.info("Mobile video upload assembled and completed: uploadId={}, chunks={}, bytes={}, assemblyDurationMs={}, status={}",
                    uploadId, chunkCount, totalBytes, elapsedMillis(assemblyStartedAt), updated.getStatus());
        } finally {
            fileLock.unlock();
        }

        if (startProcessing) {
            processor.normalize(updated.getId());
        }
        return MobileVideoUploadResponse.from(updated);
    }

    /**
     * Finalizes an interrupted recording using only the contiguous chunks that
     * are already durably stored on disk. This is intentionally conservative:
     * chunks after the first gap are ignored because MediaRecorder chunks must
     * be concatenated in their original sequence.
     */
    public MobileVideoUploadResponse recoverInterrupted(UUID uploadId, String uploadToken) throws IOException {
        MobileVideoUpload snapshot = requireAuthorized(uploadId, uploadToken);
        if (snapshot.getStatus() == MobileVideoUploadStatus.UPLOADED
                || snapshot.getStatus() == MobileVideoUploadStatus.PROCESSING
                || snapshot.getStatus() == MobileVideoUploadStatus.READY
                || snapshot.getStatus() == MobileVideoUploadStatus.CONSUMED) {
            return MobileVideoUploadResponse.from(snapshot);
        }
        if (snapshot.getStatus() != MobileVideoUploadStatus.RECORDING) {
            throw new IllegalArgumentException("Эту запись нельзя восстановить в статусе " + snapshot.getStatus());
        }

        int contiguousChunks = countContiguousPartFiles(uploadId);
        if (contiguousChunks <= 0) {
            throw new IllegalArgumentException("Не удалось восстановить запись: ни одна часть видео не была полностью загружена");
        }
        log.warn("Recovering interrupted mobile video upload from durable chunks: uploadId={}, contiguousChunks={}, bytesReceived={}",
                uploadId, contiguousChunks, snapshot.getBytesReceived());
        return complete(uploadId, uploadToken, contiguousChunks);
    }

    public MobileVideoUploadResponse status(UUID uploadId, String uploadToken) {
        return MobileVideoUploadResponse.from(requireAuthorized(uploadId, uploadToken));
    }

    public MobileVideoUploadResponse mergeSegments(MergeMobileVideoUploadsRequest request)
            throws IOException, InterruptedException {
        BitrixPlacementContext context = contextSigner.verify(request.contextToken());
        if (request.segments() == null || request.segments().isEmpty()) {
            throw new IllegalArgumentException("Нет частей записи для объединения");
        }
        if (request.segments().size() > 24) {
            throw new IllegalArgumentException("Слишком много переключений источника в одной записи");
        }

        List<MobileVideoUpload> segments = new ArrayList<>();
        Set<UUID> uniqueIds = new HashSet<>();
        for (MergeMobileVideoUploadsRequest.Segment ref : request.segments()) {
            if (!uniqueIds.add(ref.uploadId())) {
                throw new IllegalArgumentException("Одна и та же часть записи передана дважды");
            }
            MobileVideoUpload upload = requireAuthorized(ref.uploadId(), ref.uploadToken());
            if (upload.getStatus() != MobileVideoUploadStatus.READY || upload.getNormalizedFilePath() == null) {
                throw new IllegalArgumentException("Часть записи ещё не готова: " + ref.uploadId());
            }
            if (!context.memberId().equals(upload.getBitrixMemberId())
                    || context.entityType() != upload.getCrmEntityType()
                    || context.entityId() != upload.getCrmEntityId()) {
                throw new IllegalArgumentException("Части записи относятся к другой карточке Bitrix24");
            }
            Path path = Path.of(upload.getNormalizedFilePath());
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("Файл части записи не найден: " + ref.uploadId());
            }
            segments.add(upload);
        }

        if (segments.size() == 1) {
            return MobileVideoUploadResponse.from(segments.getFirst());
        }

        MobileVideoUpload merged = MobileVideoUpload.create(
                context.memberId(),
                context.entityType(),
                context.entityId(),
                "video/mp4",
                MobileVideoSourceKind.MERGED,
                null,
                uploadDirectory.toString(),
                retentionHours);
        merged = repository.saveAndFlush(merged);
        Path output = uploadDirectory.resolve(merged.getId() + ".normalized.mp4");
        List<Path> paths = segments.stream().map(item -> Path.of(item.getNormalizedFilePath())).toList();

        long startedAt = System.nanoTime();
        try {
            MobileVideoMerger.MergeResult result = merger.merge(paths, output);
            UUID mergedId = merged.getId();
            long mergedBytes = result.size();
            MobileVideoUpload ready = transactionTemplate.execute(status -> {
                MobileVideoUpload current = repository.findByIdForUpdate(mergedId)
                        .orElseThrow(() -> new NoSuchElementException("Сессия объединённой записи не найдена"));
                current.markReady(result.path().toString(), mergedBytes);
                return repository.saveAndFlush(current);
            });
            log.info("Mobile video recording segments merged: uploadId={}, segments={}, bytes={}, quality={}, durationMs={}",
                    ready.getId(), segments.size(), result.size(), result.quality(), elapsedMillis(startedAt));
            return MobileVideoUploadResponse.from(ready);
        } catch (IOException | InterruptedException | RuntimeException error) {
            UUID mergedId = merged.getId();
            transactionTemplate.executeWithoutResult(status -> repository.findByIdForUpdate(mergedId).ifPresent(current -> {
                current.markError(error.getMessage());
                repository.saveAndFlush(current);
            }));
            Files.deleteIfExists(output);
            throw error;
        }
    }

    public void discard(UUID uploadId, String uploadToken) throws IOException {
        ReentrantLock fileLock = lockFor(uploadId);
        fileLock.lock();
        try {
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
            deletePartFiles(discarded.getId());
            log.info("Mobile video upload discarded by client: uploadId={}, status={}, bytes={}",
                    discarded.getId(), discarded.getStatus(), discarded.getBytesReceived());
        } finally {
            fileLock.unlock();
            uploadFileLocks.remove(uploadId, fileLock);
        }
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
                switch (upload.getSourceKind()) {
                    case FILE -> "uploaded-file-h264";
                    case MERGED -> "mixed-recording-h264";
                    case RECORDING -> "recorded-h264";
                });

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
                deletePartFiles(upload.getId());
                uploadFileLocks.remove(upload.getId());
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

    private String normalizeMimeType(String value, MobileVideoSourceKind sourceKind) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            normalized = "application/octet-stream";
        }
        if (normalized.length() > 160) {
            throw new IllegalArgumentException("Слишком длинное описание формата видео");
        }
        if (sourceKind == MobileVideoSourceKind.FILE) {
            boolean supported = normalized.startsWith("video/mp4")
                    || normalized.startsWith("video/webm")
                    || normalized.startsWith("video/quicktime")
                    || normalized.startsWith("video/x-matroska")
                    || normalized.startsWith("video/x-m4v")
                    || "application/octet-stream".equals(normalized);
            if (!supported) {
                throw new IllegalArgumentException("Неподдерживаемый формат видеофайла. Используйте MP4, MOV, WebM, MKV или M4V");
            }
            return normalized;
        }
        if (!normalized.startsWith("video/") && !"application/octet-stream".equals(normalized)) {
            throw new IllegalArgumentException("Неподдерживаемый тип файла: " + value);
        }
        return normalized;
    }

    private long maxAllowedBytes(MobileVideoUpload upload) {
        return upload.getSourceKind() == MobileVideoSourceKind.FILE
                ? Math.min(maxManualFileBytes, maxUploadBytes)
                : maxUploadBytes;
    }

    private ReentrantLock lockFor(UUID uploadId) {
        return uploadFileLocks.computeIfAbsent(uploadId, ignored -> new ReentrantLock(true));
    }

    private Path partPath(UUID uploadId, int sequence) {
        return uploadDirectory.resolve(uploadId + ".part." + formatSequence(sequence));
    }

    private String formatSequence(int sequence) {
        return String.format(Locale.ROOT, "%06d", sequence);
    }

    private int countContiguousPartFiles(UUID uploadId) {
        int sequence = 0;
        while (sequence <= 100_000 && Files.isRegularFile(partPath(uploadId, sequence))) {
            sequence++;
        }
        return sequence;
    }

    private long calculateStoredPartBytes(UUID uploadId) throws IOException {
        long total = 0;
        String prefix = uploadId + ".part.";
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(uploadDirectory, prefix + "*")) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (name.contains(".tmp.") || !Files.isRegularFile(path)) {
                    continue;
                }
                total += Files.size(path);
            }
        }
        return total;
    }

    private void deletePartFiles(UUID uploadId) throws IOException {
        String prefix = uploadId + ".part.";
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(uploadDirectory, prefix + "*")) {
            for (Path path : stream) {
                Files.deleteIfExists(path);
            }
        }
        Files.deleteIfExists(uploadDirectory.resolve(uploadId + ".assembling"));
    }

    private void moveWithoutReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(source, target);
        }
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
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
