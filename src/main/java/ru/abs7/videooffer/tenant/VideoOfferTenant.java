package ru.abs7.videooffer.tenant;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "video_offer_tenant")
public class VideoOfferTenant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "portal_domain", nullable = false, unique = true, length = 255)
    private String portalDomain;

    @Column(name = "member_id", unique = true, length = 100)
    private String memberId;

    @Column(name = "webhook_url", columnDefinition = "text")
    private String webhookUrl;

    @Column(name = "local_client_id", length = 255)
    private String localClientId;

    @Column(name = "local_client_secret", columnDefinition = "text")
    private String localClientSecret;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TenantStatus status;

    @Column(name = "package_name", nullable = false, length = 120)
    private String packageName;

    @Column(name = "seat_limit", nullable = false)
    private Integer seatLimit;

    @Column(name = "offer_limit", nullable = false)
    private Integer offerLimit;

    @Column(name = "offers_used", nullable = false)
    private Long offersUsed;

    @Column(name = "disk_quota_bytes", nullable = false)
    private Long diskQuotaBytes;

    @Column(name = "allow_any_entity", nullable = false)
    private Boolean allowAnyEntity;

    @Column(name = "primary_admin_user_id")
    private Long primaryAdminUserId;

    @Column(name = "page_settings_json", columnDefinition = "text")
    private String pageSettingsJson;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected VideoOfferTenant() {}

    public static VideoOfferTenant create(
            String name,
            String portalDomain,
            String webhookUrl,
            String localClientId,
            String localClientSecret,
            String packageName,
            int seatLimit,
            int offerLimit,
            long diskQuotaBytes,
            boolean allowAnyEntity) {
        OffsetDateTime now = OffsetDateTime.now();
        VideoOfferTenant tenant = new VideoOfferTenant();
        tenant.name = normalizeRequired(name, "Название клиента");
        tenant.portalDomain = normalizeDomain(portalDomain);
        tenant.webhookUrl = normalize(webhookUrl);
        tenant.localClientId = normalize(localClientId);
        tenant.localClientSecret = normalize(localClientSecret);
        tenant.status = TenantStatus.ACTIVE;
        tenant.packageName = normalize(packageName) == null ? "Beta" : packageName.trim();
        tenant.seatLimit = Math.max(1, seatLimit);
        tenant.offerLimit = Math.max(1, offerLimit);
        tenant.offersUsed = 0L;
        tenant.diskQuotaBytes = Math.max(100L * 1024 * 1024, diskQuotaBytes);
        tenant.allowAnyEntity = allowAnyEntity;
        tenant.createdAt = now;
        tenant.updatedAt = now;
        return tenant;
    }

    public void updateMasterSettings(
            String name,
            String portalDomain,
            String webhookUrl,
            String localClientId,
            String localClientSecret,
            TenantStatus status,
            String packageName,
            int seatLimit,
            int offerLimit,
            long diskQuotaBytes,
            boolean allowAnyEntity) {
        this.name = normalizeRequired(name, "Название клиента");
        this.portalDomain = normalizeDomain(portalDomain);
        this.webhookUrl = normalize(webhookUrl);
        this.localClientId = normalize(localClientId);
        this.localClientSecret = normalize(localClientSecret);
        this.status = status == null ? TenantStatus.ACTIVE : status;
        this.packageName = normalize(packageName) == null ? "Beta" : packageName.trim();
        this.seatLimit = Math.max(1, seatLimit);
        this.offerLimit = Math.max(1, offerLimit);
        this.diskQuotaBytes = Math.max(100L * 1024 * 1024, diskQuotaBytes);
        this.allowAnyEntity = allowAnyEntity;
        this.updatedAt = OffsetDateTime.now();
    }

    public void bindMemberId(String memberId) {
        String normalized = normalize(memberId);
        if (normalized != null && !normalized.equals(this.memberId)) {
            this.memberId = normalized;
            this.updatedAt = OffsetDateTime.now();
        }
    }

    public void setPrimaryAdminUserId(Long userId) {
        this.primaryAdminUserId = userId != null && userId > 0 ? userId : null;
        this.updatedAt = OffsetDateTime.now();
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

    public void setPageSettingsJson(String pageSettingsJson) {
        this.pageSettingsJson = normalize(pageSettingsJson);
        this.updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getPortalDomain() { return portalDomain; }
    public String getMemberId() { return memberId; }
    public String getWebhookUrl() { return webhookUrl; }
    public String getLocalClientId() { return localClientId; }
    public String getLocalClientSecret() { return localClientSecret; }
    public TenantStatus getStatus() { return status; }
    public String getPackageName() { return packageName; }
    public Integer getSeatLimit() { return seatLimit; }
    public Integer getOfferLimit() { return offerLimit; }
    public Long getOffersUsed() { return offersUsed; }
    public Long getDiskQuotaBytes() { return diskQuotaBytes; }
    public Boolean getAllowAnyEntity() { return allowAnyEntity; }
    public Long getPrimaryAdminUserId() { return primaryAdminUserId; }
    public String getPageSettingsJson() { return pageSettingsJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public boolean isActive() { return status == TenantStatus.ACTIVE; }
    public boolean allowAnyEntity() { return Boolean.TRUE.equals(allowAnyEntity); }

    public static String normalizeDomain(String value) {
        String normalized = normalizeRequired(value, "Адрес портала")
                .toLowerCase()
                .replaceFirst("^https?://", "")
                .replaceFirst("/rest/.*$", "")
                .replaceAll("/+$", "");
        if (!normalized.contains(".")) {
            throw new IllegalArgumentException("Некорректный адрес портала Bitrix24");
        }
        return normalized;
    }

    private static String normalizeRequired(String value, String label) {
        String normalized = normalize(value);
        if (normalized == null) throw new IllegalArgumentException(label + " не указано");
        return normalized;
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
