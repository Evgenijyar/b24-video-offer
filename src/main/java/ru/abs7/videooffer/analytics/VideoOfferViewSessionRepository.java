package ru.abs7.videooffer.analytics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface VideoOfferViewSessionRepository
        extends JpaRepository<VideoOfferViewSession, UUID> {
    Optional<VideoOfferViewSession> findByOffer_IdAndSessionId(UUID offerId, String sessionId);
}
