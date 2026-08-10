package ru.abs7.videooffer.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.abs7.videooffer.kontur.KonturRecordingUrlParser;
import ru.abs7.videooffer.kontur.KonturVideoDownloader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.IntConsumer;

@Service
public class UniversalVideoDownloader {
    private static final Logger log = LoggerFactory.getLogger(UniversalVideoDownloader.class);

    private final KonturRecordingUrlParser konturParser;
    private final KonturVideoDownloader konturDownloader;
    private final YtDlpVideoDownloader ytDlpDownloader;

    public UniversalVideoDownloader(
            KonturRecordingUrlParser konturParser,
            KonturVideoDownloader konturDownloader,
            YtDlpVideoDownloader ytDlpDownloader) {
        this.konturParser = konturParser;
        this.konturDownloader = konturDownloader;
        this.ytDlpDownloader = ytDlpDownloader;
    }

    public DownloadResult download(
            String sourceUrl,
            String recordingKey,
            String targetBaseName,
            IntConsumer progressConsumer) throws IOException, InterruptedException {
        if (konturParser.isKonturRecordingUrl(sourceUrl)) {
            log.info("Universal video source resolved: type=KONTUR, targetBase={}", targetBaseName);
            KonturVideoDownloader.DownloadResult result = konturDownloader.download(
                    recordingKey,
                    targetBaseName,
                    progressConsumer::accept);
            return new DownloadResult(result.path(), result.size(), result.quality(), "KONTUR");
        }

        log.info("Universal video source resolved: type=EXTERNAL_URL, targetBase={}", targetBaseName);
        YtDlpVideoDownloader.DownloadResult result = ytDlpDownloader.download(
                sourceUrl,
                targetBaseName,
                progressConsumer);
        return new DownloadResult(result.path(), result.size(), result.quality(), "YT_DLP");
    }

    public record DownloadResult(Path path, long size, String quality, String sourceType) {
    }
}
