package ru.abs7.videooffer.tenant;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "video_offer_tenant_user",
        uniqueConstraints = @UniqueConstraint(name = "uk_video_offer_tenant_user", columnNames = {"tenant_id", "bitrix_user_id"}))
public class VideoOfferTenantUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "bitrix_user_id", nullable = false)
    private Long bitrixUserId;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(length = 255)
    private String email;

    @Column(nullable = false)
    private Boolean active;

    @Column(name = "offer_access", nullable = false)
    private Boolean offerAccess;

    @Column(nullable = false)
    private Boolean admin;

    @Column(name = "primary_admin", nullable = false)
    private Boolean primaryAdmin;

    @Column(name = "default_accompanying_text", columnDefinition = "text")
    private String defaultAccompanyingText;

    @Column(name = "default_client_message", columnDefinition = "text")
    private String defaultClientMessage;

    @Column(name = "offers_used", nullable = false)
    private Long offersUsed;

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected VideoOfferTenantUser() {}

    public static VideoOfferTenantUser fromBitrix(long tenantId, long bitrixUserId, String displayName, String email) {
        OffsetDateTime now = OffsetDateTime.now();
        VideoOfferTenantUser user = new VideoOfferTenantUser();
        user.tenantId = tenantId;
        user.bitrixUserId = bitrixUserId;
        user.displayName = normalizeName(displayName, bitrixUserId);
        user.email = normalize(email);
        user.active = true;
        user.offerAccess = false;
        user.admin = false;
        user.primaryAdmin = false;
        user.offersUsed = 0L;
        user.createdAt = now;
        user.updatedAt = now;
        return user;
    }

    public void synchronizeProfile(String displayName, String email, boolean active) {
        this.displayName = normalizeName(displayName, bitrixUserId);
        this.email = normalize(email);
        this.active = active;
        this.updatedAt = OffsetDateTime.now();
    }

    public void configure(boolean offerAccess, boolean admin, String defaultAccompanyingText, String defaultClientMessage) {
        this.offerAccess = offerAccess;
        this.admin = admin || primaryAdmin;
        this.defaultAccompanyingText = normalize(defaultAccompanyingText);
        this.defaultClientMessage = normalize(defaultClientMessage);
        this.updatedAt = OffsetDateTime.now();
    }

    public void markPrimaryAdmin(boolean value) {
        this.primaryAdmin = value;
        if (value) {
            this.admin = true;
            this.offerAccess = true;
        }
        this.updatedAt = OffsetDateTime.now();
    }

    public void touch() {
        this.lastSeenAt = OffsetDateTime.now();
        this.updatedAt = lastSeenAt;
    }

    public void incrementOffersUsed() {
        this.offersUsed = Math.max(0L, offersUsed == null ? 0L : offersUsed) + 1;
        this.updatedAt = OffsetDateTime.now();
    }

    public void decrementOffersUsed() {
        this.offersUsed = Math.max(0L, offersUsed == null ? 0L : offersUsed - 1);
        this.updatedAt = OffsetDateTime.now();
    }

    public void resetOffersUsed() {
        this.offersUsed = 0L;
        this.updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public Long getTenantId() { return tenantId; }
    public Long getBitrixUserId() { return bitrixUserId; }
    public String getDisplayName() { return displayName; }
    public String getEmail() { return email; }
    public Boolean getActive() { return active; }
    public Boolean getOfferAccess() { return offerAccess; }
    public Boolean getAdmin() { return admin; }
    public Boolean getPrimaryAdmin() { return primaryAdmin; }
    public String getDefaultAccompanyingText() { return defaultAccompanyingText; }
    public String getDefaultClientMessage() { return defaultClientMessage; }
    public Long getOffersUsed() { return offersUsed; }
    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public boolean isActive() { return Boolean.TRUE.equals(active); }
    public boolean hasOfferAccess() { return Boolean.TRUE.equals(offerAccess); }
    public boolean isAdmin() { return Boolean.TRUE.equals(admin); }
    public boolean isPrimaryAdmin() { return Boolean.TRUE.equals(primaryAdmin); }

    private static String normalizeName(String value, long userId) {
        String normalized = normalize(value);
        return normalized == null ? "Сотрудник Bitrix24 #" + userId : normalized;
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
