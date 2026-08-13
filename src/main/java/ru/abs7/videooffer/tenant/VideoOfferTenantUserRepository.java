package ru.abs7.videooffer.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface VideoOfferTenantUserRepository extends JpaRepository<VideoOfferTenantUser, Long> {
    Optional<VideoOfferTenantUser> findByTenantIdAndBitrixUserId(Long tenantId, Long bitrixUserId);
    List<VideoOfferTenantUser> findAllByTenantIdOrderByDisplayNameAsc(Long tenantId);
    long countByTenantIdAndOfferAccessTrueAndActiveTrue(Long tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from VideoOfferTenantUser user where user.tenantId = :tenantId and user.bitrixUserId = :userId")
    Optional<VideoOfferTenantUser> findForUpdate(@Param("tenantId") Long tenantId, @Param("userId") Long userId);
}
