package ru.abs7.videooffer.kontur;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

@Service
public class KonturVideoDownloader {
    private static final Logger log = LoggerFactory.getLogger(KonturVideoDownloader.class);

    private static final int SINGLE_COPY_BUFFER_SIZE = 8 * 1024 * 1024;
    private static final int PARALLEL_COPY_BUFFER_SIZE = 2 * 1024 * 1024;
    private static final long PARALLEL_DOWNLOAD_THRESHOLD_BYTES = 32L * 1024L * 1024L;
    private static final long TARGET_PART_SIZE_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_PARALLEL_PARTS = 8;
    private static final List<String> QUALITIES = List.of("900 p", "900p", "720p", "1080p", "high");

    private final RestClient client;
    private final Path storageDir;
    private final ExecutorService rangeExecutor = Executors.newFixedThreadPool(MAX_PARALLEL_PARTS);

    public KonturVideoDownloader(
            KonturTalkProperties properties,
            @Value("${app.video.storage-dir}") String storageDir) {
        String baseUrl = properties.apiUrl().endsWith("/") ? properties.apiUrl() : properties.apiUrl() + "/";
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Auth-Token", properties.apiToken())
                .build();
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
    }

    @PreDestroy
    public void shutdown() {
        rangeExecutor.shutdownNow();
    }

    public DownloadResult download(String recordingKey, String offerId, IntConsumer progress) throws Exception {
        Files.createDirectories(storageDir);
        Path tempFile = storageDir.resolve(offerId + ".download");
        Path finalFile = storageDir.resolve(offerId + ".mp4");
        Exception lastError = null;

        for (String quality : QUALITIES) {
            try {
                Files.deleteIfExists(tempFile);
                Probe probe = probe(recordingKey, quality);
                if (probe.statusCode() == 404) {
                    continue;
                }
                if (probe.statusCode() >= 400) {
                    log.warn("Контур.Толк вернул status={} для recordingKey={}, quality={}",
                            probe.statusCode(), recordingKey, quality);
                    continue;
                }

                log.info("Начинаем загрузку записи: recordingKey={}, quality={}, bytes={}, ranges={}",
                        recordingKey, quality, probe.totalBytes(), probe.supportsRanges());

                long downloadedBytes;
                if (probe.supportsRanges() && probe.totalBytes() > PARALLEL_DOWNLOAD_THRESHOLD_BYTES) {
                    downloadedBytes = downloadByRanges(
                            recordingKey, quality, tempFile, probe.totalBytes(), progress);
                } else {
                    downloadedBytes = downloadSingleStream(
                            recordingKey, quality, tempFile, probe.totalBytes(), progress);
                }

                if (downloadedBytes <= 0) {
                    Files.deleteIfExists(tempFile);
                    continue;
                }
                if (probe.totalBytes() > 0 && downloadedBytes != probe.totalBytes()) {
                    Files.deleteIfExists(tempFile);
                    throw new IllegalStateException(
                            "Скачан неполный файл: downloaded=" + downloadedBytes + ", expected=" + probe.totalBytes());
                }

                moveAtomically(tempFile, finalFile);
                progress.accept(100);
                log.info("Видео Контур.Толка сохранено: file={}, bytes={}, quality={}",
                        finalFile, downloadedBytes, quality);
                return new DownloadResult(finalFile, downloadedBytes, quality);
            } catch (Exception error) {
                lastError = error;
                Files.deleteIfExists(tempFile);
                log.warn("Не удалось скачать качество {} для записи {}: {}",
                        quality, recordingKey, rootMessage(error));
            }
        }

        throw new IllegalStateException("Видео не удалось скачать ни в одном доступном качестве", lastError);
    }

    private Probe probe(String recordingKey, String quality) {
        try {
            return client.get()
                    .uri("/api/Recordings/{key}/file/{quality}", recordingKey, quality)
                    .headers(headers -> {
                        headers.set(HttpHeaders.ACCEPT_ENCODING, "identity");
                        headers.set(HttpHeaders.RANGE, "bytes=0-0");
                    })
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 206) {
                            long total = parseTotalFromContentRange(
                                    response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
                            return new Probe(status, total, total > 0);
                        }
                        long contentLength = response.getHeaders().getContentLength();
                        return new Probe(status, Math.max(0, contentLength), false);
                    });
        } catch (Exception error) {
            log.warn("Ошибка проверки видео Контур.Толка: recordingKey={}, quality={}, error={}",
                    recordingKey, quality, rootMessage(error));
            return new Probe(500, 0, false);
        }
    }

    private long downloadByRanges(
            String recordingKey,
            String quality,
            Path tempFile,
            long totalBytes,
            IntConsumer progress) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(tempFile.toFile(), "rw")) {
            file.setLength(totalBytes);
        }

        int partCount = calculatePartCount(totalBytes);
        long partSize = (long) Math.ceil(totalBytes / (double) partCount);
        AtomicLong downloaded = new AtomicLong();
        List<Future<?>> futures = new ArrayList<>();

        for (int index = 0; index < partCount; index++) {
            long start = index * partSize;
            long end = Math.min(totalBytes - 1, start + partSize - 1);
            int partNumber = index + 1;
            futures.add(rangeExecutor.submit(() -> downloadRangePart(
                    recordingKey,
                    quality,
                    tempFile,
                    start,
                    end,
                    partNumber,
                    downloaded,
                    totalBytes,
                    progress)));
        }

        try {
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            futures.forEach(future -> future.cancel(true));
            throw new IllegalStateException("Загрузка видео была прервана", error);
        } catch (ExecutionException error) {
            futures.forEach(future -> future.cancel(true));
            Throwable cause = error.getCause() == null ? error : error.getCause();
            throw new IllegalStateException("Ошибка параллельной загрузки: " + rootMessage(cause), cause);
        }

        long actualSize = Files.size(tempFile);
        if (actualSize != totalBytes) {
            throw new IllegalStateException(
                    "Размер файла после параллельной загрузки не совпал: actual=" + actualSize
                            + ", expected=" + totalBytes);
        }
        return actualSize;
    }

    private void downloadRangePart(
            String recordingKey,
            String quality,
            Path tempFile,
            long start,
            long end,
            int partNumber,
            AtomicLong downloaded,
            long totalBytes,
            IntConsumer progress) {
        String range = "bytes=" + start + "-" + end;
        client.get()
                .uri("/api/Recordings/{key}/file/{quality}", recordingKey, quality)
                .headers(headers -> {
                    headers.set(HttpHeaders.ACCEPT_ENCODING, "identity");
                    headers.set(HttpHeaders.RANGE, range);
                })
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status != 206) {
                        throw new IllegalStateException(
                                "Контур.Толк вернул status=" + status + " для части " + partNumber);
                    }

                    long position = start;
                    byte[] bytes = new byte[PARALLEL_COPY_BUFFER_SIZE];
                    try (InputStream input = response.getBody();
                         FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.WRITE)) {
                        if (input == null) {
                            throw new IllegalStateException("Контур.Толк вернул пустое тело ответа");
                        }
                        int read;
                        while ((read = input.read(bytes)) != -1) {
                            if (Thread.currentThread().isInterrupted()) {
                                throw new IllegalStateException("Загрузка части была прервана");
                            }
                            ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, read);
                            while (buffer.hasRemaining()) {
                                int written = channel.write(buffer, position);
                                if (written <= 0) {
                                    throw new IllegalStateException("Не удалось записать часть видео на диск");
                                }
                                position += written;
                            }
                            long current = downloaded.addAndGet(read);
                            progress.accept(percent(current, totalBytes));
                        }
                    }

                    long expectedPartSize = end - start + 1;
                    long actualPartSize = position - start;
                    if (actualPartSize != expectedPartSize) {
                        throw new IllegalStateException(
                                "Часть " + partNumber + " скачана не полностью: actual=" + actualPartSize
                                        + ", expected=" + expectedPartSize);
                    }
                    return null;
                });
    }

    private long downloadSingleStream(
            String recordingKey,
            String quality,
            Path tempFile,
            long expectedLength,
            IntConsumer progress) {
        Long result = client.get()
                .uri("/api/Recordings/{key}/file/{quality}", recordingKey, quality)
                .headers(headers -> headers.set(HttpHeaders.ACCEPT_ENCODING, "identity"))
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status >= 400) {
                        throw new IllegalStateException("Контур.Толк вернул status=" + status);
                    }
                    long total = expectedLength > 0
                            ? expectedLength
                            : Math.max(0, response.getHeaders().getContentLength());
                    long copied = 0;
                    byte[] bytes = new byte[SINGLE_COPY_BUFFER_SIZE];
                    try (InputStream input = response.getBody();
                         OutputStream output = Files.newOutputStream(
                                 tempFile,
                                 StandardOpenOption.CREATE,
                                 StandardOpenOption.TRUNCATE_EXISTING,
                                 StandardOpenOption.WRITE)) {
                        if (input == null) {
                            throw new IllegalStateException("Контур.Толк вернул пустое тело ответа");
                        }
                        int read;
                        while ((read = input.read(bytes)) != -1) {
                            if (Thread.currentThread().isInterrupted()) {
                                throw new IllegalStateException("Загрузка видео была прервана");
                            }
                            output.write(bytes, 0, read);
                            copied += read;
                            if (total > 0) {
                                progress.accept(percent(copied, total));
                            }
                        }
                        output.flush();
                    }
                    return copied;
                });
        return result == null ? 0 : result;
    }

    private int calculatePartCount(long totalBytes) {
        int bySize = (int) Math.ceil(totalBytes / (double) TARGET_PART_SIZE_BYTES);
        return Math.max(2, Math.min(MAX_PARALLEL_PARTS, bySize));
    }

    private int percent(long downloaded, long total) {
        if (total <= 0) {
            return 1;
        }
        return (int) Math.max(1, Math.min(99, downloaded * 100 / total));
    }

    private long parseTotalFromContentRange(String contentRange) {
        if (contentRange == null || contentRange.isBlank()) {
            return 0;
        }
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0 || slash == contentRange.length() - 1) {
            return 0;
        }
        String total = contentRange.substring(slash + 1).trim();
        if ("*".equals(total)) {
            return 0;
        }
        try {
            return Long.parseLong(total);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void moveAtomically(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record Probe(int statusCode, long totalBytes, boolean supportsRanges) {
    }

    public record DownloadResult(Path path, long size, String quality) {
    }
}
