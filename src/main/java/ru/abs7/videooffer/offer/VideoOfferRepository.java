package ru.abs7.videooffer.offer;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VideoOfferRepository extends JpaRepository<VideoOffer, UUID> {
    Optional<VideoOffer> findByPublicToken(String publicToken);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select offer from VideoOffer offer where offer.publicToken = :publicToken")
    Optional<VideoOffer> findByPublicTokenForUpdate(@Param("publicToken") String publicToken);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select offer from VideoOffer offer where offer.id = :id")
    Optional<VideoOffer> findByIdForUpdate(@Param("id") UUID id);

    List<VideoOffer> findTop20ByOrderByCreatedAtDesc();

    List<VideoOffer> findAllByStatusIn(Collection<VideoOfferStatus> statuses);

    List<VideoOffer> findAllByExpiresAtBefore(OffsetDateTime moment);

    @Query("""
            select offer from VideoOffer offer
            where offer.status = :readyStatus
              and offer.bitrixDeliveryStatus in :statuses
              and offer.bitrixMemberId is not null
              and offer.expiresAt > :moment
            order by offer.updatedAt asc
            """)
    List<VideoOffer> findBitrixDeliveriesForRetry(
            @Param("readyStatus") VideoOfferStatus readyStatus,
            @Param("statuses") Collection<String> statuses,
            @Param("moment") OffsetDateTime moment,
            Pageable pageable);

    @Query("""
            select offer from VideoOffer offer
            where offer.status = :readyStatus
              and offer.bitrixDeliveryStatus = :sendingStatus
              and offer.updatedAt < :staleBefore
              and offer.expiresAt > :moment
            order by offer.updatedAt asc
            """)
    List<VideoOffer> findStaleBitrixDeliveries(
            @Param("readyStatus") VideoOfferStatus readyStatus,
            @Param("sendingStatus") String sendingStatus,
            @Param("staleBefore") OffsetDateTime staleBefore,
            @Param("moment") OffsetDateTime moment,
            Pageable pageable);

    @Query("""
            select offer from VideoOffer offer
            where offer.viewNotificationStatus in :statuses
              and offer.viewGoalReachedAt is not null
              and offer.expiresAt > :moment
            order by offer.viewGoalReachedAt asc
            """)
    List<VideoOffer> findViewNotificationsForRetry(
            @Param("statuses") Collection<ViewNotificationStatus> statuses,
            @Param("moment") OffsetDateTime moment,
            Pageable pageable);

    @Query("""
            select offer from VideoOffer offer
            where offer.viewNotificationStatus = :status
              and offer.updatedAt < :staleBefore
              and offer.expiresAt > :moment
            order by offer.updatedAt asc
            """)
    List<VideoOffer> findStaleViewNotifications(
            @Param("status") ViewNotificationStatus status,
            @Param("staleBefore") OffsetDateTime staleBefore,
            @Param("moment") OffsetDateTime moment,
            Pageable pageable);
}
