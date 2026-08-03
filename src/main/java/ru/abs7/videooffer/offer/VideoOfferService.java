package ru.abs7.videooffer.offer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.abs7.videooffer.kontur.KonturRecordingUrlParser;
import java.util.UUID;

@Service
public class VideoOfferService {
    private final VideoOfferRepository repository;
    private final KonturRecordingUrlParser parser;
    private final VideoOfferProcessor processor;
    private final String publicBaseUrl;
    private final int retentionDays;

    public VideoOfferService(VideoOfferRepository repository, KonturRecordingUrlParser parser, VideoOfferProcessor processor,
            @Value("${app.public-base-url}") String publicBaseUrl,
            @Value("${app.video.retention-days:30}") int retentionDays) {
        this.repository=repository; this.parser=parser; this.processor=processor;
        this.publicBaseUrl=publicBaseUrl.replaceAll("/+$", ""); this.retentionDays=retentionDays;
    }

    @Transactional
    public VideoOffer create(CreateVideoOfferRequest request) {
        String key=parser.extractRecordingKey(request.recordingUrl());
        VideoOffer offer=VideoOffer.create(request.entityType(), request.entityId(), request.bitrixMemberId(), request.bitrixUserId(), request.recordingUrl().trim(), key, request.accompanyingText(), retentionDays);
        repository.saveAndFlush(offer);
        processor.process(offer.getId());
        return offer;
    }

    public VideoOffer get(UUID id){return repository.findById(id).orElseThrow();}
    public VideoOffer getByToken(String token){return repository.findByPublicToken(token).orElseThrow();}
    public VideoOfferResponse response(VideoOffer offer){return VideoOfferResponse.from(offer, publicBaseUrl);}
}
