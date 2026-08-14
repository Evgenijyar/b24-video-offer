package ru.abs7.videooffer.analytics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface VideoOfferEventRepository extends JpaRepository<VideoOfferEvent, Long> {
    @Query("""
            select distinct event.videoOffer.id
            from VideoOfferEvent event
            where event.videoOffer.id in :offerIds
              and event.eventType = :eventType
            """)
    List<UUID> findOfferIdsWithEvent(
            @Param("offerIds") Collection<UUID> offerIds,
            @Param("eventType") VideoOfferEventType eventType);
}
