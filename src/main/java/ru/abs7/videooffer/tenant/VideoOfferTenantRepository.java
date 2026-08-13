package ru.abs7.videooffer.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface VideoOfferTenantRepository extends JpaRepository<VideoOfferTenant, Long> {
    Optional<VideoOfferTenant> findByPortalDomainIgnoreCase(String portalDomain);
    Optional<VideoOfferTenant> findByMemberId(String memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select tenant from VideoOfferTenant tenant where tenant.id = :id")
    Optional<VideoOfferTenant> findByIdForUpdate(@Param("id") Long id);
}
