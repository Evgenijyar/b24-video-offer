package ru.abs7.videooffer.offer;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "video_offer")
public class VideoOffer {
    @Id private UUID id;
    @Column(name="public_token", nullable=false, unique=true, length=80) private String publicToken;
    @Enumerated(EnumType.STRING) @Column(name="crm_entity_type", nullable=false, length=20) private CrmEntityType crmEntityType;
    @Column(name="crm_entity_id", nullable=false) private Long crmEntityId;
    @Column(name="bitrix_member_id", length=100) private String bitrixMemberId;
    @Column(name="bitrix_user_id") private Long bitrixUserId;
    @Column(name="source_recording_url", nullable=false, columnDefinition="text") private String sourceRecordingUrl;
    @Column(name="recording_key", nullable=false, length=255) private String recordingKey;
    @Column(name="accompanying_text", columnDefinition="text") private String accompanyingText;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=30) private VideoOfferStatus status;
    @Column(name="progress_percent", nullable=false) private Integer progressPercent;
    @Column(name="video_file_path", columnDefinition="text") private String videoFilePath;
    @Column(name="video_file_size") private Long videoFileSize;
    @Column(name="video_quality", length=30) private String videoQuality;
    @Column(name="error_message", columnDefinition="text") private String errorMessage;
    @Column(name="created_at", nullable=false) private OffsetDateTime createdAt;
    @Column(name="updated_at", nullable=false) private OffsetDateTime updatedAt;
    @Column(name="ready_at") private OffsetDateTime readyAt;
    @Column(name="expires_at") private OffsetDateTime expiresAt;
    @Column(name="bitrix_delivery_status", nullable=false, length=30) private String bitrixDeliveryStatus;
    @Column(name="bitrix_timeline_comment_id") private Long bitrixTimelineCommentId;
    @Column(name="bitrix_delivery_error", columnDefinition="text") private String bitrixDeliveryError;
    @Column(name="bitrix_delivered_at") private OffsetDateTime bitrixDeliveredAt;

    @Enumerated(EnumType.STRING)
    @Column(name="view_notification_goal", nullable=false, length=30)
    private ViewNotificationGoal viewNotificationGoal;

    @Enumerated(EnumType.STRING)
    @Column(name="view_notification_status", nullable=false, length=30)
    private ViewNotificationStatus viewNotificationStatus;

    @Column(name="view_goal_reached_at") private OffsetDateTime viewGoalReachedAt;
    @Column(name="view_goal_session_id", length=100) private String viewGoalSessionId;
    @Column(name="view_goal_position_seconds", precision=12, scale=3) private BigDecimal viewGoalPositionSeconds;
    @Column(name="view_goal_duration_seconds", precision=12, scale=3) private BigDecimal viewGoalDurationSeconds;
    @Column(name="view_notification_comment_id") private Long viewNotificationCommentId;
    @Column(name="view_notification_error", columnDefinition="text") private String viewNotificationError;
    @Column(name="view_notification_sent_at") private OffsetDateTime viewNotificationSentAt;

    protected VideoOffer() {}

    public static VideoOffer create(
            CrmEntityType type,
            long entityId,
            String memberId,
            Long userId,
            String url,
            String key,
            String text,
            ViewNotificationGoal requestedGoal,
            int retentionDays) {
        VideoOffer v = new VideoOffer();
        v.id=UUID.randomUUID(); v.publicToken=UUID.randomUUID().toString().replace("-", "");
        v.crmEntityType=type; v.crmEntityId=entityId; v.bitrixMemberId=memberId; v.bitrixUserId=userId;
        v.sourceRecordingUrl=url; v.recordingKey=key; v.accompanyingText=text;
        v.status=VideoOfferStatus.QUEUED; v.progressPercent=0; v.createdAt=OffsetDateTime.now(); v.updatedAt=v.createdAt;
        v.expiresAt=v.createdAt.plusDays(retentionDays);
        v.bitrixDeliveryStatus = memberId == null || memberId.isBlank() ? "NOT_REQUIRED" : "PENDING";
        v.viewNotificationGoal = ViewNotificationGoal.orDefault(requestedGoal);
        v.viewNotificationStatus = memberId == null || memberId.isBlank()
                || v.viewNotificationGoal == ViewNotificationGoal.NONE
                ? ViewNotificationStatus.NOT_REQUIRED
                : ViewNotificationStatus.WAITING;
        return v;
    }

    public void markPreparing(int progress) { status=VideoOfferStatus.PREPARING; progressPercent=Math.max(0, Math.min(99, progress)); updatedAt=OffsetDateTime.now(); }
    public void markReady(String path, long size, String quality) { status=VideoOfferStatus.READY; progressPercent=100; videoFilePath=path; videoFileSize=size; videoQuality=quality; readyAt=OffsetDateTime.now(); updatedAt=readyAt; errorMessage=null; }
    public void markError(String message) { status=VideoOfferStatus.ERROR; errorMessage=message; updatedAt=OffsetDateTime.now(); }
    public void markBitrixDelivered(Long commentId) { bitrixDeliveryStatus="DELIVERED"; bitrixTimelineCommentId=commentId; bitrixDeliveryError=null; bitrixDeliveredAt=OffsetDateTime.now(); updatedAt=bitrixDeliveredAt; }
    public void markBitrixDeliveryError(String message) { bitrixDeliveryStatus="ERROR"; bitrixDeliveryError=message; updatedAt=OffsetDateTime.now(); }
    public void markBitrixDeliveryNotRequired() { bitrixDeliveryStatus="NOT_REQUIRED"; bitrixDeliveryError=null; updatedAt=OffsetDateTime.now(); }

    public boolean markViewGoalReached(
            String sessionId,
            BigDecimal positionSeconds,
            BigDecimal durationSeconds) {
        if (viewGoalReachedAt != null) {
            return false;
        }
        viewGoalReachedAt = OffsetDateTime.now();
        viewGoalSessionId = sessionId;
        viewGoalPositionSeconds = positionSeconds;
        viewGoalDurationSeconds = durationSeconds;
        viewNotificationError = null;
        viewNotificationStatus = bitrixMemberId == null || bitrixMemberId.isBlank()
                || viewNotificationGoal == ViewNotificationGoal.NONE
                ? ViewNotificationStatus.NOT_REQUIRED
                : ViewNotificationStatus.PENDING;
        updatedAt = viewGoalReachedAt;
        return true;
    }

    public boolean claimViewNotification() {
        if (viewGoalReachedAt == null) {
            return false;
        }
        if (viewNotificationStatus != ViewNotificationStatus.PENDING
                && viewNotificationStatus != ViewNotificationStatus.ERROR) {
            return false;
        }
        viewNotificationStatus = ViewNotificationStatus.SENDING;
        viewNotificationError = null;
        updatedAt = OffsetDateTime.now();
        return true;
    }

    public void markViewNotificationDelivered(Long commentId) {
        viewNotificationStatus = ViewNotificationStatus.DELIVERED;
        viewNotificationCommentId = commentId;
        viewNotificationError = null;
        viewNotificationSentAt = OffsetDateTime.now();
        updatedAt = viewNotificationSentAt;
    }

    public void markViewNotificationError(String message) {
        viewNotificationStatus = ViewNotificationStatus.ERROR;
        viewNotificationError = message;
        updatedAt = OffsetDateTime.now();
    }

    public void releaseStaleViewNotification(String message) {
        if (viewNotificationStatus == ViewNotificationStatus.SENDING) {
            viewNotificationStatus = ViewNotificationStatus.ERROR;
            viewNotificationError = message;
            updatedAt = OffsetDateTime.now();
        }
    }

    public UUID getId(){return id;} public String getPublicToken(){return publicToken;} public CrmEntityType getCrmEntityType(){return crmEntityType;} public Long getCrmEntityId(){return crmEntityId;} public String getBitrixMemberId(){return bitrixMemberId;} public Long getBitrixUserId(){return bitrixUserId;} public String getSourceRecordingUrl(){return sourceRecordingUrl;} public String getRecordingKey(){return recordingKey;} public String getAccompanyingText(){return accompanyingText;} public VideoOfferStatus getStatus(){return status;} public Integer getProgressPercent(){return progressPercent;} public String getVideoFilePath(){return videoFilePath;} public Long getVideoFileSize(){return videoFileSize;} public String getVideoQuality(){return videoQuality;} public String getErrorMessage(){return errorMessage;} public OffsetDateTime getCreatedAt(){return createdAt;} public OffsetDateTime getUpdatedAt(){return updatedAt;} public OffsetDateTime getReadyAt(){return readyAt;} public OffsetDateTime getExpiresAt(){return expiresAt;} public String getBitrixDeliveryStatus(){return bitrixDeliveryStatus;} public Long getBitrixTimelineCommentId(){return bitrixTimelineCommentId;} public String getBitrixDeliveryError(){return bitrixDeliveryError;} public OffsetDateTime getBitrixDeliveredAt(){return bitrixDeliveredAt;}
    public ViewNotificationGoal getViewNotificationGoal(){return viewNotificationGoal;} public ViewNotificationStatus getViewNotificationStatus(){return viewNotificationStatus;} public OffsetDateTime getViewGoalReachedAt(){return viewGoalReachedAt;} public String getViewGoalSessionId(){return viewGoalSessionId;} public BigDecimal getViewGoalPositionSeconds(){return viewGoalPositionSeconds;} public BigDecimal getViewGoalDurationSeconds(){return viewGoalDurationSeconds;} public Long getViewNotificationCommentId(){return viewNotificationCommentId;} public String getViewNotificationError(){return viewNotificationError;} public OffsetDateTime getViewNotificationSentAt(){return viewNotificationSentAt;}
}
