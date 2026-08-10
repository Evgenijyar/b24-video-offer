package ru.abs7.videooffer.source;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.abs7.videooffer.bitrix.mobile.upload.MobileVideoTranscoder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class YtDlpVideoDownloader {
    private static final Logger log = LoggerFactory.getLogger(YtDlpVideoDownloader.class);
    private static final Pattern PROGRESS_PATTERN = Pattern.compile("__PROGRESS__:\\s*([0-9]+(?:\\.[0-9]+)?)%");
    private static final String FILE_PREFIX = "__FILE__:";

    private final MobileVideoTranscoder transcoder;
    private final Path videoStorageDir;
    private final Path importDirectory;
    private final String ytDlpExecutable;
    private final String denoExecutable;
    private final Duration timeout;
    private final long maxDownloadBytes;

    public YtDlpVideoDownloader(
            MobileVideoTranscoder transcoder,
            @Value("${app.video.storage-dir:./data/videos}") String videoStorageDir,
            @Value("${app.external-video.yt-dlp-path:/usr/local/bin/yt-dlp}") String ytDlpExecutable,
            @Value("${app.external-video.deno-path:/usr/local/bin/deno}") String denoExecutable,
            @Value("${app.external-video.timeout-minutes:30}") long timeoutMinutes,
            @Value("${app.external-video.max-download-bytes:1073741824}") long maxDownloadBytes) throws IOException {
        this.transcoder = transcoder;
        this.videoStorageDir = Path.of(videoStorageDir).toAbsolutePath().normalize();
        Path dataDir = this.videoStorageDir.getParent() == null ? this.videoStorageDir : this.videoStorageDir.getParent();
        this.importDirectory = dataDir.resolve("external-imports");
        Files.createDirectories(this.videoStorageDir);
        Files.createDirectories(this.importDirectory);
        this.ytDlpExecutable = ytDlpExecutable;
        this.denoExecutable = denoExecutable;
        this.timeout = Duration.ofMinutes(Math.max(2, timeoutMinutes));
        this.maxDownloadBytes = Math.max(32L * 1024 * 1024, maxDownloadBytes);
    }

    @PostConstruct
    void verifyTools() {
        String ytVersion = executableVersion(ytDlpExecutable, "--version", "yt-dlp");
        String denoVersion = executableVersion(denoExecutable, "--version", "Deno");
        log.info("External video tools ready: ytDlp={}, ytDlpVersion={}, deno={}, denoVersion={}, maxDownloadBytes={}",
                ytDlpExecutable, ytVersion, denoExecutable, denoVersion, maxDownloadBytes);
    }

    public DownloadResult download(
            String rawUrl,
            String targetBaseName,
            IntConsumer progressConsumer) throws IOException, InterruptedException {
        URI sourceUri = validatePublicHttpUrl(rawUrl);
        cleanupTargetArtifacts(targetBaseName);

        Path outputTemplate = importDirectory.resolve(targetBaseName + ".%(ext)s");
        List<String> command = new ArrayList<>();
        command.add(ytDlpExecutable);
        command.add("--no-config");
        command.add("--no-playlist");
        command.add("--newline");
        command.add("--no-colors");
        command.add("--progress");
        command.add("--progress-template");
        command.add("download:__PROGRESS__:%(progress._percent_str)s");
        command.add("--print");
        command.add("after_move:__FILE__:%(filepath)s");
        command.add("--output");
        command.add(outputTemplate.toString());
        command.add("--merge-output-format");
        command.add("mp4");
        command.add("--remux-video");
        command.add("mp4");
        command.add("--format");
        command.add("bv*[height<=1080]+ba/b[height<=1080]/best[height<=1080]/best");
        command.add("--concurrent-fragments");
        command.add("4");
        command.add("--retries");
        command.add("5");
        command.add("--fragment-retries");
        command.add("5");
        command.add("--socket-timeout");
        command.add("20");
        command.add("--max-filesize");
        command.add(Long.toString(maxDownloadBytes));
        command.add("--js-runtimes");
        command.add("deno:" + denoExecutable);
        command.add(sourceUri.toString());

        log.info("Starting external video download: host={}, targetBase={}, timeoutMinutes={}",
                sourceUri.getHost(), targetBaseName, timeout.toMinutes());
        long startedAt = System.nanoTime();
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

        StringBuilder diagnostics = new StringBuilder();
        AtomicReference<Path> reportedFileRef = new AtomicReference<>();
        AtomicInteger lastProgress = new AtomicInteger(-1);
        Thread outputReader = Thread.ofVirtual().name("yt-dlp-output-" + targetBaseName).start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (diagnostics) {
                        if (diagnostics.length() < 48_000) {
                            diagnostics.append(line).append('\n');
                        }
                    }
                    Matcher matcher = PROGRESS_PATTERN.matcher(line);
                    if (matcher.find()) {
                        try {
                            int percent = Math.max(0, Math.min(90,
                                    (int) Math.floor(Double.parseDouble(matcher.group(1).trim()) * 0.90)));
                            int previous = lastProgress.getAndUpdate(old -> Math.max(old, percent));
                            if (percent > previous) {
                                progressConsumer.accept(percent);
                            }
                        } catch (RuntimeException ignored) { }
                    }
                    if (line.startsWith(FILE_PREFIX)) {
                        String value = line.substring(FILE_PREFIX.length()).trim();
                        if (!value.isBlank()) {
                            try {
                                reportedFileRef.set(Path.of(value).toAbsolutePath().normalize());
                            } catch (RuntimeException ignored) { }
                        }
                    }
                }
            } catch (IOException error) {
                synchronized (diagnostics) {
                    if (diagnostics.length() < 48_000) {
                        diagnostics.append("[reader-error] ").append(error.getMessage()).append('\n');
                    }
                }
            }
        });

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
            outputReader.join(1500);
            cleanupTargetArtifacts(targetBaseName);
            throw new IOException("Загрузка видео по ссылке превысила лимит времени " + timeout.toMinutes() + " минут");
        }
        outputReader.join(3000);
        String diagnosticsText;
        synchronized (diagnostics) {
            diagnosticsText = diagnostics.toString();
        }
        if (process.exitValue() != 0) {
            cleanupTargetArtifacts(targetBaseName);
            throw new IOException("Не удалось загрузить видео по ссылке (yt-dlp, код " + process.exitValue() + ")"
                    + diagnosticSuffix(diagnosticsText));
        }

        Path reportedFile = reportedFileRef.get();
        Path downloaded = locateDownloadedFile(targetBaseName, reportedFile);
        long downloadedBytes = Files.size(downloaded);
        if (downloadedBytes <= 0) {
            throw new IOException("Видеохостинг вернул пустой файл");
        }
        if (downloadedBytes > maxDownloadBytes) {
            Files.deleteIfExists(downloaded);
            throw new IllegalArgumentException("Видео по ссылке слишком большое. Максимальный размер загрузки — "
                    + Math.round(maxDownloadBytes / 1024.0 / 1024.0) + " МБ");
        }

        progressConsumer.accept(92);
        Path normalized = videoStorageDir.resolve(targetBaseName + ".mp4");
        String mime = downloaded.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mp4")
                ? "video/mp4" : "application/octet-stream";
        MobileVideoTranscoder.TranscodeResult normalizedResult = transcoder.transcode(downloaded, normalized, mime);
        progressConsumer.accept(99);
        Files.deleteIfExists(downloaded);
        cleanupTargetArtifacts(targetBaseName);

        log.info("External video download completed: host={}, targetBase={}, bytes={}, quality={}, durationMs={}",
                sourceUri.getHost(), targetBaseName, normalizedResult.size(), normalizedResult.quality(), elapsedMillis(startedAt));
        return new DownloadResult(normalizedResult.path(), normalizedResult.size(), "external-" + normalizedResult.quality());
    }

    private URI validatePublicHttpUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("Ссылка на видео не передана");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Некорректная ссылка на видео", error);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Ссылка на видео должна начинаться с http:// или https://");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Ссылки с логином/паролем в URL не поддерживаются");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (!isPublicAddress(address)) {
                    throw new IllegalArgumentException("Внутренние и локальные сетевые адреса не поддерживаются");
                }
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("Не удалось определить адрес видеохостинга", error);
        }
        return uri;
    }

    private boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            int first = bytes[0] & 0xff;
            if ((first & 0xfe) == 0xfc) return false; // IPv6 unique-local fc00::/7
        } else if (bytes.length == 4) {
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;
            if (a == 0 || a >= 224) return false;
            if (a == 100 && b >= 64 && b <= 127) return false; // CGNAT
            if (a == 198 && (b == 18 || b == 19)) return false; // benchmark network
        }
        return true;
    }

    private Path locateDownloadedFile(String targetBaseName, Path reportedFile) throws IOException {
        if (reportedFile != null && Files.isRegularFile(reportedFile) && reportedFile.startsWith(importDirectory)) {
            return reportedFile;
        }
        Path best = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(importDirectory, targetBaseName + ".*")) {
            for (Path candidate : stream) {
                if (!Files.isRegularFile(candidate)) continue;
                String name = candidate.getFileName().toString();
                if (name.endsWith(".part") || name.contains(".ytdl")) continue;
                if (best == null || Files.getLastModifiedTime(candidate).compareTo(Files.getLastModifiedTime(best)) > 0) {
                    best = candidate;
                }
            }
        }
        if (best == null) {
            throw new IOException("yt-dlp завершился без итогового видеофайла");
        }
        return best;
    }

    private void cleanupTargetArtifacts(String targetBaseName) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(importDirectory, targetBaseName + ".*")) {
            for (Path path : stream) {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    private String executableVersion(String executable, String argument, String label) {
        Path path = Path.of(executable);
        if (!Files.isRegularFile(path) || !Files.isExecutable(path)) {
            throw new IllegalStateException(label + " is required but is not executable: " + executable);
        }
        try {
            Process process = new ProcessBuilder(executable, argument).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!process.waitFor(8, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                throw new IllegalStateException(label + " availability check failed");
            }
            return output.lines().findFirst().orElse(label + " available");
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Cannot start " + label + ": " + executable, error);
        }
    }

    private String diagnosticSuffix(String output) {
        String details = output == null ? "" : output.trim();
        if (details.length() > 2400) {
            details = details.substring(details.length() - 2400);
        }
        return details.isBlank() ? "" : ": " + details;
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    public record DownloadResult(Path path, long size, String quality) {
    }
}
