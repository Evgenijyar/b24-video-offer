package ru.abs7.videooffer.analytics;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VideoOfferEventRepository extends JpaRepository<VideoOfferEvent, Long> {
}
