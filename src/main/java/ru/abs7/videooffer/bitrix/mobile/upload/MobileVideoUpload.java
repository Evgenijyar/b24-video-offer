package ru.abs7.videooffer.bitrix.mobile.upload;

import jakarta.persistence.*;
import ru.abs7.videooffer.offer.CrmEntityType;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "mobile_video_upload")
public class MobileVideoUpload {
    @Id
    private UUID id;

    @Column(name = "upload_token", nullable = false, unique = true, length = 100)
    private String uploadToken;

    @Column(name = "bitrix_member_id", nullable = false, length = 100)
    private String bitrixMemberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "crm_entity_type", nullable = false, length = 20)
    private CrmEntityType crmEntityType;

    @Column(name = "crm_entity_id", nullable = false)
    private Long crmEntityId;

    @Column(name = "mime_type", nullable = false, length = 160)
    private String mimeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", nullable = false, length = 20)
    private MobileVideoSourceKind sourceKind;

    @Column(name = "declared_size_bytes")
    private Long declaredSizeBytes;

    @Column(name = "source_file_path", nullable = false, columnDefinition = "text")
    private String sourceFilePath;

    @Column(name = "normalized_file_path", columnDefinition = "text")
    private String normalizedFilePath;

    @Column(name = "bytes_received", nullable = false)
    private Long bytesReceived;

    @Column(name = "next_sequence", nullable = false)
    private Integer nextSequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MobileVideoUploadStatus status;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "video_offer_id")
    private UUID videoOfferId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "ready_at")
    private OffsetDateTime readyAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    protected MobileVideoUpload() {
    }

    public static MobileVideoUpload create(
            String memberId,
            CrmEntityType entityType,
            long entityId,
            String mimeType,
            MobileVideoSourceKind sourceKind,
            Long declaredSizeBytes,
            String sourceDirectory,
            int retentionHours) {
        MobileVideoUpload upload = new MobileVideoUpload();
        upload.id = UUID.randomUUID();
        upload.uploadToken = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        upload.bitrixMemberId = memberId;
        upload.crmEntityType = entityType;
        upload.crmEntityId = entityId;
        upload.mimeType = mimeType;
        upload.sourceKind = MobileVideoSourceKind.orDefault(sourceKind);
        upload.declaredSizeBytes = declaredSizeBytes;
        upload.sourceFilePath = Path.of(sourceDirectory).resolve(upload.id + ".source").toString();
        upload.bytesReceived = 0L;
        upload.nextSequence = 0;
        upload.status = MobileVideoUploadStatus.RECORDING;
        upload.createdAt = OffsetDateTime.now();
        upload.updatedAt = upload.createdAt;
        upload.expiresAt = upload.createdAt.plusHours(retentionHours);
        return upload;
    }

    /**
     * Records upload progress without requiring network chunks to arrive in sequence.
     * Chunks are assembled in order only when the client calls complete().
     */
    public void acceptChunk(int sequence, long totalBytes) {
        if (status != MobileVideoUploadStatus.RECORDING) {
            throw new IllegalArgumentException("Запись уже завершена и больше не принимает данные");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("Некорректный номер части видео");
        }
        bytesReceived = Math.max(0L, totalBytes);
        nextSequence = Math.max(nextSequence, sequence + 1);
        updatedAt = OffsetDateTime.now();
    }

    public void markUploaded(long totalBytes, int chunkCount) {
        if (status == MobileVideoUploadStatus.UPLOADED
                || status == MobileVideoUploadStatus.PROCESSING
                || status == MobileVideoUploadStatus.READY
                || status == MobileVideoUploadStatus.CONSUMED) {
            return;
        }
        if (status != MobileVideoUploadStatus.RECORDING) {
            throw new IllegalArgumentException("Эту запись нельзя завершить в статусе " + status);
        }
        if (totalBytes <= 0 || chunkCount <= 0) {
            throw new IllegalArgumentException("Видео не содержит данных");
        }
        bytesReceived = totalBytes;
        nextSequence = chunkCount;
        status = MobileVideoUploadStatus.UPLOADED;
        updatedAt = OffsetDateTime.now();
    }

    public void markProcessing() {
        if (status != MobileVideoUploadStatus.UPLOADED) {
            return;
        }
        status = MobileVideoUploadStatus.PROCESSING;
        errorMessage = null;
        updatedAt = OffsetDateTime.now();
    }

    public void markReady(String normalizedPath) {
        markReady(normalizedPath, bytesReceived == null ? 0L : bytesReceived);
    }

    public void markReady(String normalizedPath, long readyBytes) {
        status = MobileVideoUploadStatus.READY;
        normalizedFilePath = normalizedPath;
        bytesReceived = Math.max(0L, readyBytes);
        errorMessage = null;
        readyAt = OffsetDateTime.now();
        updatedAt = readyAt;
    }

    public void markConsumed(UUID offerId) {
        status = MobileVideoUploadStatus.CONSUMED;
        videoOfferId = offerId;
        updatedAt = OffsetDateTime.now();
    }

    public void markError(String message) {
        status = MobileVideoUploadStatus.ERROR;
        errorMessage = message;
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getUploadToken() { return uploadToken; }
    public String getBitrixMemberId() { return bitrixMemberId; }
    public CrmEntityType getCrmEntityType() { return crmEntityType; }
    public Long getCrmEntityId() { return crmEntityId; }
    public String getMimeType() { return mimeType; }
    public MobileVideoSourceKind getSourceKind() { return sourceKind; }
    public Long getDeclaredSizeBytes() { return declaredSizeBytes; }
    public String getSourceFilePath() { return sourceFilePath; }
    public String getNormalizedFilePath() { return normalizedFilePath; }
    public Long getBytesReceived() { return bytesReceived; }
    public Integer getNextSequence() { return nextSequence; }
    public MobileVideoUploadStatus getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public UUID getVideoOfferId() { return videoOfferId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public OffsetDateTime getReadyAt() { return readyAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
}
