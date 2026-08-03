package ru.abs7.videooffer.offer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VideoOfferRepository extends JpaRepository<VideoOffer, UUID> {
    Optional<VideoOffer> findByPublicToken(String publicToken);

    List<VideoOffer> findTop20ByOrderByCreatedAtDesc();

    List<VideoOffer> findAllByStatusIn(Collection<VideoOfferStatus> statuses);

    List<VideoOffer> findAllByExpiresAtBefore(OffsetDateTime moment);
}
