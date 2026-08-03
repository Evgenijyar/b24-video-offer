package ru.abs7.videooffer.offer;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.abs7.videooffer.kontur.KonturVideoDownloader;
import java.util.UUID;

@Service
public class VideoOfferProcessor {
    private final VideoOfferRepository repository;
    private final KonturVideoDownloader downloader;

    public VideoOfferProcessor(VideoOfferRepository repository, KonturVideoDownloader downloader) {
        this.repository=repository; this.downloader=downloader;
    }

    @Async
    public void process(UUID id) {
        VideoOffer offer=repository.findById(id).orElseThrow();
        try {
            updateProgress(id,1);
            KonturVideoDownloader.DownloadResult result=downloader.download(offer.getRecordingKey(), id.toString(), p -> updateProgress(id,p));
            VideoOffer current=repository.findById(id).orElseThrow();
            current.markReady(result.path().toString(),result.size(),result.quality());
            repository.save(current);
        } catch (Exception e) {
            repository.findById(id).ifPresent(current -> { current.markError(rootMessage(e)); repository.save(current); });
        }
    }

    private void updateProgress(UUID id,int progress) {
        repository.findById(id).ifPresent(current -> { current.markPreparing(progress); repository.save(current); });
    }

    private String rootMessage(Throwable error) {
        Throwable current=error;
        while(current.getCause()!=null) current=current.getCause();
        return current.getMessage()==null ? error.getClass().getSimpleName() : current.getMessage();
    }
}
