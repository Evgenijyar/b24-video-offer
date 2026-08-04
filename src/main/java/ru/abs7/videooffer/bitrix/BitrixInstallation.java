package ru.abs7.videooffer.bitrix;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "bitrix_installation")
public class BitrixInstallation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false, unique = true, length = 100)
    private String memberId;

    @Column(name = "portal_domain", nullable = false, length = 255)
    private String portalDomain;

    @Column(name = "access_token", columnDefinition = "text")
    private String accessToken;

    @Column(name = "refresh_token", columnDefinition = "text")
    private String refreshToken;

    @Column(name = "token_expires_at")
    private OffsetDateTime tokenExpiresAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected BitrixInstallation() {
    }

    public static BitrixInstallation create(
            String memberId,
            String portalDomain,
            String accessToken,
            String refreshToken,
            OffsetDateTime tokenExpiresAt) {
        OffsetDateTime now = OffsetDateTime.now();
        BitrixInstallation installation = new BitrixInstallation();
        installation.memberId = memberId;
        installation.portalDomain = portalDomain;
        installation.accessToken = accessToken;
        installation.refreshToken = refreshToken;
        installation.tokenExpiresAt = tokenExpiresAt;
        installation.createdAt = now;
        installation.updatedAt = now;
        return installation;
    }

    public void updateAuthorization(
            String portalDomain,
            String accessToken,
            String refreshToken,
            OffsetDateTime tokenExpiresAt) {
        this.portalDomain = portalDomain;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.tokenExpiresAt = tokenExpiresAt;
        this.updatedAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getMemberId() {
        return memberId;
    }

    public String getPortalDomain() {
        return portalDomain;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public OffsetDateTime getTokenExpiresAt() {
        return tokenExpiresAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
