package ru.abs7.videooffer.kontur;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final int connectTimeoutSeconds;
    private final int readTimeoutSeconds;
    private final int stallWarningSeconds;
    private final int progressLogStepPercent;
    private final int progressLogIntervalSeconds;
    private final ExecutorService rangeExecutor;
    private final ScheduledExecutorService monitorExecutor;

    public KonturVideoDownloader(
            KonturTalkProperties properties,
            @Value("${app.video.storage-dir}") String storageDir) {
        validateProperties(properties);

        this.connectTimeoutSeconds = properties.connectTimeoutSecondsOrDefault();
        this.readTimeoutSeconds = properties.readTimeoutSecondsOrDefault();
        this.stallWarningSeconds = properties.stallWarningSecondsOrDefault();
        this.progressLogStepPercent = properties.progressLogStepPercentOrDefault();
        this.progressLogIntervalSeconds = properties.progressLogIntervalSecondsOrDefault();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        requestFactory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));

        String baseUrl = properties.apiUrl().endsWith("/")
                ? properties.apiUrl()
                : properties.apiUrl() + "/";
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("X-Auth-Token", properties.apiToken())
                .build();
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
        this.rangeExecutor = Executors.newFixedThreadPool(
                MAX_PARALLEL_PARTS,
                namedThreadFactory("kontur-range-"));
        this.monitorExecutor = Executors.newSingleThreadScheduledExecutor(
                namedThreadFactory("kontur-monitor-"));

        log.info("Kontur downloader initialized: baseUrl={}, storageDir={}, connectTimeoutSeconds={}, "
                        + "readTimeoutSeconds={}, stallWarningSeconds={}, maxParallelParts={}, "
                        + "parallelThresholdBytes={}",
                baseUrl,
                storageDir,
                connectTimeoutSeconds,
                readTimeoutSeconds,
                stallWarningSeconds,
                MAX_PARALLEL_PARTS,
                PARALLEL_DOWNLOAD_THRESHOLD_BYTES);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Stopping Kontur downloader executors");
        rangeExecutor.shutdownNow();
        monitorExecutor.shutdownNow();
    }

    public DownloadResult download(String recordingKey, String offerId, IntConsumer progress) throws Exception {
        long downloadStartedAt = System.nanoTime();
        Files.createDirectories(storageDir);
        Path tempFile = storageDir.resolve(offerId + ".download");
        Path finalFile = storageDir.resolve(offerId + ".mp4");
        Exception lastError = null;

        log.info("Kontur download requested: offerId={}, recordingKey={}, storageDir={}, tempFile={}, finalFile={}, "
                        + "qualities={}",
                offerId, recordingKey, storageDir, tempFile, finalFile, QUALITIES);

        try (DownloadWatchdog watchdog = new DownloadWatchdog(offerId, recordingKey)) {
            for (int qualityIndex = 0; qualityIndex < QUALITIES.size(); qualityIndex++) {
                String quality = QUALITIES.get(qualityIndex);
                long qualityStartedAt = System.nanoTime();
                watchdog.stage("probing quality " + quality);

                try {
                    Files.deleteIfExists(tempFile);
                    log.info("Kontur quality attempt started: offerId={}, recordingKey={}, attempt={}/{}, quality={}",
                            offerId, recordingKey, qualityIndex + 1, QUALITIES.size(), quality);

                    Probe probe = probe(recordingKey, quality, offerId);
                    if (probe.statusCode() == 404) {
                        log.info("Kontur quality not found: offerId={}, recordingKey={}, quality={}, status=404",
                                offerId, recordingKey, quality);
                        continue;
                    }
                    if (probe.statusCode() >= 400) {
                        log.warn("Kontur probe returned error status: offerId={}, recordingKey={}, quality={}, "
                                        + "status={}, contentLength={}, contentRange={}",
                                offerId,
                                recordingKey,
                                quality,
                                probe.statusCode(),
                                probe.totalBytes(),
                                probe.contentRange());
                        continue;
                    }

                    String mode = probe.supportsRanges()
                            && probe.totalBytes() > PARALLEL_DOWNLOAD_THRESHOLD_BYTES
                            ? "PARALLEL_RANGES"
                            : "SINGLE_STREAM";
                    log.info("Kontur quality selected for download attempt: offerId={}, recordingKey={}, quality={}, "
                                    + "status={}, totalBytes={}, totalHuman={}, supportsRanges={}, mode={}",
                            offerId,
                            recordingKey,
                            quality,
                            probe.statusCode(),
                            probe.totalBytes(),
                            humanBytes(probe.totalBytes()),
                            probe.supportsRanges(),
                            mode);

                    ProgressReporter reporter = new ProgressReporter(
                            offerId,
                            recordingKey,
                            quality,
                            mode,
                            progress,
                            progressLogStepPercent,
                            progressLogIntervalSeconds,
                            watchdog);
                    reporter.report(0, probe.totalBytes(), "download-start");

                    long downloadedBytes;
                    if ("PARALLEL_RANGES".equals(mode)) {
                        downloadedBytes = downloadByRanges(
                                recordingKey,
                                quality,
                                offerId,
                                tempFile,
                                probe.totalBytes(),
                                reporter,
                                watchdog);
                    } else {
                        downloadedBytes = downloadSingleStream(
                                recordingKey,
                                quality,
                                offerId,
                                tempFile,
                                probe.totalBytes(),
                                reporter,
                                watchdog);
                    }

                    log.info("Kontur transfer body finished: offerId={}, recordingKey={}, quality={}, downloadedBytes={}, "
                                    + "downloadedHuman={}, expectedBytes={}, durationMs={}",
                            offerId,
                            recordingKey,
                            quality,
                            downloadedBytes,
                            humanBytes(downloadedBytes),
                            probe.totalBytes(),
                            elapsedMillis(qualityStartedAt));

                    if (downloadedBytes <= 0) {
                        Files.deleteIfExists(tempFile);
                        throw new IllegalStateException("Контур.Толк вернул пустой видеофайл");
                    }
                    if (probe.totalBytes() > 0 && downloadedBytes != probe.totalBytes()) {
                        Files.deleteIfExists(tempFile);
                        throw new IllegalStateException(
                                "Скачан неполный файл: downloaded=" + downloadedBytes
                                        + ", expected=" + probe.totalBytes());
                    }

                    watchdog.stage("moving completed file");
                    log.info("Moving downloaded file: offerId={}, source={}, target={}, bytes={}",
                            offerId, tempFile, finalFile, downloadedBytes);
                    moveAtomically(tempFile, finalFile);
                    reporter.complete(downloadedBytes);

                    log.info("Kontur video saved successfully: offerId={}, recordingKey={}, file={}, bytes={}, "
                                    + "humanSize={}, quality={}, totalDurationMs={}",
                            offerId,
                            recordingKey,
                            finalFile,
                            downloadedBytes,
                            humanBytes(downloadedBytes),
                            quality,
                            elapsedMillis(downloadStartedAt));
                    return new DownloadResult(finalFile, downloadedBytes, quality);
                } catch (Exception error) {
                    lastError = error;
                    Files.deleteIfExists(tempFile);
                    log.error("Kontur quality attempt failed: offerId={}, recordingKey={}, quality={}, durationMs={}, error={}",
                            offerId,
                            recordingKey,
                            quality,
                            elapsedMillis(qualityStartedAt),
                            rootMessage(error),
                            error);
                }
            }
        }

        log.error("Kontur download failed for all qualities: offerId={}, recordingKey={}, qualities={}, totalDurationMs={}",
                offerId,
                recordingKey,
                QUALITIES,
                elapsedMillis(downloadStartedAt),
                lastError);
        throw new IllegalStateException("Видео не удалось скачать ни в одном доступном качестве", lastError);
    }

    private Probe probe(String recordingKey, String quality, String offerId) {
        long startedAt = System.nanoTime();
        log.info("Kontur probe request: offerId={}, recordingKey={}, quality={}, range=bytes=0-0",
                offerId, recordingKey, quality);

        Probe probe = client.get()
                .uri("/api/Recordings/{key}/file/{quality}", recordingKey, quality)
                .headers(headers -> {
                    headers.set(HttpHeaders.ACCEPT_ENCODING, "identity");
                    headers.set(HttpHeaders.RANGE, "bytes=0-0");
                })
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    String contentRange = response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE);
                    long contentLength = response.getHeaders().getContentLength();
                    long total = status == 206
                            ? parseTotalFromContentRange(contentRange)
                            : Math.max(0, contentLength);
                    boolean supportsRanges = status == 206 && total > 0;

                    log.info("Kontur probe response: offerId={}, recordingKey={}, quality={}, status={}, "
                                    + "contentRange={}, contentLength={}, totalBytes={}, supportsRanges={}, durationMs={}",
                            offerId,
                            recordingKey,
                            quality,
                            status,
                            contentRange,
                            contentLength,
                            total,
                            supportsRanges,
                            elapsedMillis(startedAt));
                    return new Probe(status, total, supportsRanges, contentRange);
                });

        if (probe == null) {
            throw new IllegalStateException("Контур.Толк вернул пустой результат Range-проверки");
        }
        return probe;
    }

    private long downloadByRanges(
            String recordingKey,
            String quality,
            String offerId,
            Path tempFile,
            long totalBytes,
            ProgressReporter reporter,
            DownloadWatchdog watchdog) throws Exception {
        long startedAt = System.nanoTime();
        watchdog.stage("preallocating range file");
        log.info("Preparing parallel range download file: offerId={}, file={}, totalBytes={}, totalHuman={}",
                offerId, tempFile, totalBytes, humanBytes(totalBytes));

        try (RandomAccessFile file = new RandomAccessFile(tempFile.toFile(), "rw")) {
            file.setLength(totalBytes);
        }

        int partCount = calculatePartCount(totalBytes);
        long partSize = (long) Math.ceil(totalBytes / (double) partCount);
        AtomicLong downloaded = new AtomicLong();
        List<Future<?>> futures = new ArrayList<>();

        log.info("Parallel range plan: offerId={}, recordingKey={}, quality={}, partCount={}, targetPartSize={}, "
                        + "calculatedPartSize={}",
                offerId, recordingKey, quality, partCount, TARGET_PART_SIZE_BYTES, partSize);

        for (int index = 0; index < partCount; index++) {
            long start = index * partSize;
            long end = Math.min(totalBytes - 1, start + partSize - 1);
            int partNumber = index + 1;
            log.info("Submitting range part: offerId={}, part={}/{}, range=bytes={}-{}, expectedBytes={}",
                    offerId, partNumber, partCount, start, end, end - start + 1);
            futures.add(rangeExecutor.submit(() -> downloadRangePart(
                    recordingKey,
                    quality,
                    offerId,
                    tempFile,
                    start,
                    end,
                    partNumber,
                    partCount,
                    downloaded,
                    totalBytes,
                    reporter,
                    watchdog)));
        }

        try {
            for (int index = 0; index < futures.size(); index++) {
                log.info("Waiting for range part future: offerId={}, part={}/{}",
                        offerId, index + 1, futures.size());
                futures.get(index).get();
                log.info("Range part future completed: offerId={}, part={}/{}",
                        offerId, index + 1, futures.size());
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            futures.forEach(future -> future.cancel(true));
            log.error("Parallel range download interrupted: offerId={}, downloadedBytes={}, totalBytes={}",
                    offerId, downloaded.get(), totalBytes, error);
            throw new IllegalStateException("Загрузка видео была прервана", error);
        } catch (ExecutionException | CancellationException error) {
            futures.forEach(future -> future.cancel(true));
            Throwable cause = error instanceof ExecutionException && error.getCause() != null
                    ? error.getCause()
                    : error;
            log.error("Parallel range download failed: offerId={}, downloadedBytes={}, totalBytes={}, error={}",
                    offerId, downloaded.get(), totalBytes, rootMessage(cause), cause);
            throw new IllegalStateException("Ошибка параллельной загрузки: " + rootMessage(cause), cause);
        }

        long actualSize = Files.size(tempFile);
        if (actualSize != totalBytes) {
            throw new IllegalStateException(
                    "Размер файла после параллельной загрузки не совпал: actual=" + actualSize
                            + ", expected=" + totalBytes);
        }

        log.info("Parallel range download completed: offerId={}, file={}, actualSize={}, durationMs={}",
                offerId, tempFile, actualSize, elapsedMillis(startedAt));
        return actualSize;
    }

    private void downloadRangePart(
            String recordingKey,
            String quality,
            String offerId,
            Path tempFile,
            long start,
            long end,
            int partNumber,
            int partCount,
            AtomicLong downloaded,
            long totalBytes,
            ProgressReporter reporter,
            DownloadWatchdog watchdog) {
        long startedAt = System.nanoTime();
        String range = "bytes=" + start + "-" + end;
        long expectedPartSize = end - start + 1;
        String stage = "range-part-" + partNumber + "-of-" + partCount;
        watchdog.stage(stage + " requesting");

        log.info("Range part request started: offerId={}, recordingKey={}, quality={}, part={}/{}, range={}, "
                        + "expectedBytes={}, thread={}",
                offerId,
                recordingKey,
                quality,
                partNumber,
                partCount,
                range,
                expectedPartSize,
                Thread.currentThread().getName());

        try {
            client.get()
                    .uri("/api/Recordings/{key}/file/{quality}", recordingKey, quality)
                    .headers(headers -> {
                        headers.set(HttpHeaders.ACCEPT_ENCODING, "identity");
                        headers.set(HttpHeaders.RANGE, range);
                    })
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        String contentRange = response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE);
                        long contentLength = response.getHeaders().getContentLength();
                        log.info("Range part response headers: offerId={}, part={}/{}, status={}, contentRange={}, "
                                        + "contentLength={}, durationToHeadersMs={}",
                                offerId,
                                partNumber,
                                partCount,
                                status,
                                contentRange,
                                contentLength,
                                elapsedMillis(startedAt));

                        if (status != 206) {
                            throw new IllegalStateException(
                                    "Контур.Толк вернул status=" + status + " для части " + partNumber);
                        }

                        watchdog.stage(stage + " reading");
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
                                watchdog.activity(current, stage);
                                reporter.report(current, totalBytes, stage);
                            }
                        }

                        long actualPartSize = position - start;
                        if (actualPartSize != expectedPartSize) {
                            throw new IllegalStateException(
                                    "Часть " + partNumber + " скачана не полностью: actual=" + actualPartSize
                                            + ", expected=" + expectedPartSize);
                        }
                        return null;
                    });

            log.info("Range part completed: offerId={}, part={}/{}, range={}, bytes={}, humanSize={}, durationMs={}",
                    offerId,
                    partNumber,
                    partCount,
                    range,
                    expectedPartSize,
                    humanBytes(expectedPartSize),
                    elapsedMillis(startedAt));
        } catch (Exception error) {
            log.error("Range part failed: offerId={}, recordingKey={}, quality={}, part={}/{}, range={}, "
                            + "durationMs={}, error={}",
                    offerId,
                    recordingKey,
                    quality,
                    partNumber,
                    partCount,
                    range,
                    elapsedMillis(startedAt),
                    rootMessage(error),
                    error);
            if (error instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Ошибка загрузки Range-части: " + rootMessage(error), error);
        }
    }

    private long downloadSingleStream(
            String recordingKey,
            String quality,
            String offerId,
            Path tempFile,
            long expectedLength,
            ProgressReporter reporter,
            DownloadWatchdog watchdog) {
        long startedAt = System.nanoTime();
        watchdog.stage("single-stream requesting");
        log.info("Single stream request started: offerId={}, recordingKey={}, quality={}, expectedLength={}, file={}",
                offerId, recordingKey, quality, expectedLength, tempFile);

        try {
            Long result = client.get()
                    .uri("/api/Recordings/{key}/file/{quality}", recordingKey, quality)
                    .headers(headers -> headers.set(HttpHeaders.ACCEPT_ENCODING, "identity"))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        long contentLength = response.getHeaders().getContentLength();
                        String contentRange = response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE);
                        log.info("Single stream response headers: offerId={}, recordingKey={}, quality={}, status={}, "
                                        + "contentLength={}, contentRange={}, durationToHeadersMs={}",
                                offerId,
                                recordingKey,
                                quality,
                                status,
                                contentLength,
                                contentRange,
                                elapsedMillis(startedAt));

                        if (status >= 400) {
                            throw new IllegalStateException("Контур.Толк вернул status=" + status);
                        }
                        long total = expectedLength > 0
                                ? expectedLength
                                : Math.max(0, contentLength);
                        long copied = 0;
                        byte[] bytes = new byte[SINGLE_COPY_BUFFER_SIZE];
                        watchdog.stage("single-stream reading");

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
                                watchdog.activity(copied, "single-stream reading");
                                reporter.report(copied, total, "single-stream");
                            }
                            output.flush();
                        }
                        return copied;
                    });

            long copied = result == null ? 0 : result;
            log.info("Single stream completed: offerId={}, recordingKey={}, quality={}, copiedBytes={}, humanSize={}, "
                            + "durationMs={}",
                    offerId,
                    recordingKey,
                    quality,
                    copied,
                    humanBytes(copied),
                    elapsedMillis(startedAt));
            return copied;
        } catch (Exception error) {
            log.error("Single stream failed: offerId={}, recordingKey={}, quality={}, durationMs={}, error={}",
                    offerId,
                    recordingKey,
                    quality,
                    elapsedMillis(startedAt),
                    rootMessage(error),
                    error);
            if (error instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Ошибка однопоточной загрузки: " + rootMessage(error), error);
        }
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
            log.info("Atomic file move completed: source={}, target={}", source, target);
        } catch (AtomicMoveNotSupportedException error) {
            log.warn("Atomic file move is not supported, using regular replacement: source={}, target={}",
                    source, target);
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void validateProperties(KonturTalkProperties properties) {
        if (properties.apiUrl() == null || properties.apiUrl().isBlank()) {
            throw new IllegalStateException("app.talk.api-url не настроен");
        }
        if (properties.apiToken() == null || properties.apiToken().isBlank()) {
            throw new IllegalStateException("app.talk.api-token не настроен");
        }
    }

    private ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((failedThread, error) ->
                    log.error("Uncaught downloader thread error: thread={}, error={}",
                            failedThread.getName(), rootMessage(error), error));
            return thread;
        };
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private String humanBytes(long bytes) {
        if (bytes < 0) {
            return "unknown";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024.0 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.2f %s", value, units[unit]);
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    private final class DownloadWatchdog implements AutoCloseable {
        private final String offerId;
        private final String recordingKey;
        private final AtomicLong lastActivityNanos = new AtomicLong(System.nanoTime());
        private final AtomicLong downloadedBytes = new AtomicLong();
        private volatile String stage = "created";
        private final ScheduledFuture<?> task;

        private DownloadWatchdog(String offerId, String recordingKey) {
            this.offerId = offerId;
            this.recordingKey = recordingKey;
            long period = Math.max(5, Math.min(stallWarningSeconds, 15));
            this.task = monitorExecutor.scheduleAtFixedRate(
                    this::check,
                    period,
                    period,
                    TimeUnit.SECONDS);
        }

        private void activity(long bytes, String newStage) {
            downloadedBytes.set(Math.max(downloadedBytes.get(), bytes));
            stage(newStage);
        }

        private void stage(String newStage) {
            this.stage = newStage;
            lastActivityNanos.set(System.nanoTime());
        }

        private void check() {
            long idleSeconds = TimeUnit.NANOSECONDS.toSeconds(
                    System.nanoTime() - lastActivityNanos.get());
            if (idleSeconds >= stallWarningSeconds) {
                log.warn("Kontur download has no activity: offerId={}, recordingKey={}, stage={}, idleSeconds={}, "
                                + "downloadedBytes={}, downloadedHuman={}, readTimeoutSeconds={}",
                        offerId,
                        recordingKey,
                        stage,
                        idleSeconds,
                        downloadedBytes.get(),
                        humanBytes(downloadedBytes.get()),
                        readTimeoutSeconds);
            }
        }

        @Override
        public void close() {
            task.cancel(false);
        }
    }

    private final class ProgressReporter {
        private final String offerId;
        private final String recordingKey;
        private final String quality;
        private final String mode;
        private final IntConsumer externalProgress;
        private final int stepPercent;
        private final long intervalNanos;
        private final DownloadWatchdog watchdog;
        private int lastLoggedPercent = -1;
        private long lastLoggedAt = System.nanoTime();
        private long startedAt = System.nanoTime();

        private ProgressReporter(
                String offerId,
                String recordingKey,
                String quality,
                String mode,
                IntConsumer externalProgress,
                int stepPercent,
                int intervalSeconds,
                DownloadWatchdog watchdog) {
            this.offerId = offerId;
            this.recordingKey = recordingKey;
            this.quality = quality;
            this.mode = mode;
            this.externalProgress = externalProgress;
            this.stepPercent = Math.max(1, stepPercent);
            this.intervalNanos = TimeUnit.SECONDS.toNanos(Math.max(1, intervalSeconds));
            this.watchdog = watchdog;
        }

        private synchronized void report(long downloaded, long total, String stage) {
            int currentPercent = percent(downloaded, total);
            externalProgress.accept(currentPercent);
            watchdog.activity(downloaded, stage);

            long now = System.nanoTime();
            boolean percentReached = lastLoggedPercent < 0
                    || currentPercent >= lastLoggedPercent + stepPercent;
            boolean intervalReached = now - lastLoggedAt >= intervalNanos;
            if (!percentReached && !intervalReached && currentPercent < 99) {
                return;
            }

            lastLoggedPercent = currentPercent;
            lastLoggedAt = now;
            log.info("Kontur download progress: offerId={}, recordingKey={}, quality={}, mode={}, stage={}, "
                            + "progress={}%, downloadedBytes={}, downloadedHuman={}, totalBytes={}, totalHuman={}, "
                            + "elapsedMs={}",
                    offerId,
                    recordingKey,
                    quality,
                    mode,
                    stage,
                    currentPercent,
                    downloaded,
                    humanBytes(downloaded),
                    total,
                    humanBytes(total),
                    elapsedMillis(startedAt));
        }

        private synchronized void complete(long totalBytes) {
            externalProgress.accept(100);
            watchdog.activity(totalBytes, "completed");
            log.info("Kontur download progress: offerId={}, recordingKey={}, quality={}, mode={}, stage=completed, "
                            + "progress=100%, downloadedBytes={}, downloadedHuman={}, elapsedMs={}",
                    offerId,
                    recordingKey,
                    quality,
                    mode,
                    totalBytes,
                    humanBytes(totalBytes),
                    elapsedMillis(startedAt));
        }
    }

    private record Probe(int statusCode, long totalBytes, boolean supportsRanges, String contentRange) {
    }

    public record DownloadResult(Path path, long size, String quality) {
    }
}
