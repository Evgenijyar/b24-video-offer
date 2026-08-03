package ru.abs7.videooffer.kontur;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.List;
import java.util.function.IntConsumer;

@Service
public class KonturVideoDownloader {
    private static final int BUFFER_SIZE = 8 * 1024 * 1024;
    private final RestClient client;
    private final Path storageDir;

    public KonturVideoDownloader(KonturTalkProperties properties, @Value("${app.video.storage-dir}") String storageDir) {
        String base = properties.apiUrl().endsWith("/") ? properties.apiUrl() : properties.apiUrl() + "/";
        this.client = RestClient.builder().baseUrl(base).defaultHeader("X-Auth-Token", properties.apiToken()).build();
        this.storageDir = Path.of(storageDir);
    }

    public DownloadResult download(String recordingKey, String offerId, IntConsumer progress) throws Exception {
        Files.createDirectories(storageDir);
        Exception last = null;
        for (String quality : List.of("900 p", "900p", "720p", "1080p", "high")) {
            try {
                Probe probe = probe(recordingKey, quality);
                if (probe.status >= 400) continue;
                Path temp = storageDir.resolve(offerId + ".download");
                Path target = storageDir.resolve(offerId + ".mp4");
                Files.deleteIfExists(temp);
                long copied = client.get().uri("/api/Recordings/{key}/file/{quality}", recordingKey, quality)
                        .headers(h -> h.set(HttpHeaders.ACCEPT_ENCODING, "identity"))
                        .exchange((request, response) -> {
                            if (response.getStatusCode().isError()) throw new IllegalStateException("Контур.Толк вернул " + response.getStatusCode());
                            long total = response.getHeaders().getContentLength();
                            try (InputStream in=response.getBody(); OutputStream out=Files.newOutputStream(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                                byte[] buffer=new byte[BUFFER_SIZE]; long done=0; int read;
                                while ((read=in.read(buffer))!=-1) { out.write(buffer,0,read); done+=read; if(total>0) progress.accept((int)Math.min(99, done*100/total)); }
                                return done;
                            }
                        });
                if (copied <= 0) { Files.deleteIfExists(temp); continue; }
                try { Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING); }
                progress.accept(100);
                return new DownloadResult(target, copied, quality);
            } catch (Exception e) { last=e; }
        }
        throw new IllegalStateException("Видео не удалось скачать ни в одном качестве", last);
    }

    private Probe probe(String key, String quality) {
        return client.get().uri("/api/Recordings/{key}/file/{quality}", key, quality)
                .headers(h -> { h.set(HttpHeaders.ACCEPT_ENCODING,"identity"); h.set(HttpHeaders.RANGE,"bytes=0-0"); })
                .exchange((request,response) -> new Probe(response.getStatusCode().value(), response.getHeaders().getContentLength()));
    }
    private record Probe(int status, long totalBytes) {}
    public record DownloadResult(Path path, long size, String quality) {}
}
