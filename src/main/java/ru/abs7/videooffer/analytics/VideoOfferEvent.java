package ru.abs7.videooffer.analytics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import ru.abs7.videooffer.offer.VideoOffer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "video_offer_event")
public class VideoOfferEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "video_offer_id", nullable = false)
    private VideoOffer videoOffer;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private VideoOfferEventType eventType;

    @Column(name = "playback_position_seconds", precision = 12, scale = 3)
    private BigDecimal playbackPositionSeconds;

    @Column(name = "user_agent", columnDefinition = "text")
    private String userAgent;

    @Column(name = "ip_hash", length = 128)
    private String ipHash;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    protected VideoOfferEvent() {
    }

    public static VideoOfferEvent create(
            VideoOffer offer,
            VideoOfferEventType type,
            BigDecimal position,
            String userAgent,
            String ipHash) {
        VideoOfferEvent event = new VideoOfferEvent();
        event.videoOffer = offer;
        event.eventType = type;
        event.playbackPositionSeconds = position;
        event.userAgent = userAgent;
        event.ipHash = ipHash;
        event.occurredAt = OffsetDateTime.now();
        return event;
    }
}
