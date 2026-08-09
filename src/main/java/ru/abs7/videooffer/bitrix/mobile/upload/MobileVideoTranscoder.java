package ru.abs7.videooffer.bitrix.mobile.upload;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class MobileVideoTranscoder {
    private static final Logger log = LoggerFactory.getLogger(MobileVideoTranscoder.class);

    private final Duration timeout;
    private final Semaphore transcodeSlots;
    private final String ffmpegExecutable;

    public MobileVideoTranscoder(
            @Value("${app.mobile-video.ffmpeg-timeout-minutes:20}") long timeoutMinutes,
            @Value("${app.mobile-video.max-concurrent-transcodes:1}") int maxConcurrentTranscodes,
            @Value("${app.mobile-video.ffmpeg-path:/usr/bin/ffmpeg}") String ffmpegExecutable) {
        this.timeout = Duration.ofMinutes(Math.max(2, timeoutMinutes));
        this.transcodeSlots = new Semaphore(Math.max(1, Math.min(4, maxConcurrentTranscodes)), true);
        this.ffmpegExecutable = ffmpegExecutable == null || ffmpegExecutable.isBlank()
                ? "/usr/bin/ffmpeg"
                : ffmpegExecutable.trim();
    }

    @PostConstruct
    void verifyFfmpegAvailable() {
        Path executable = Path.of(ffmpegExecutable);
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            throw new IllegalStateException("FFmpeg is required for mobile video processing but is not executable: "
                    + ffmpegExecutable);
        }

        Process process = null;
        try {
            process = new ProcessBuilder(ffmpegExecutable, "-version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("FFmpeg availability check timed out: " + ffmpegExecutable);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("FFmpeg availability check failed with code " + process.exitValue());
            }
            String firstLine = output.lines().findFirst().orElse("ffmpeg available");
            log.info("FFmpeg ready for mobile video processing: executable={}, version={}",
                    ffmpegExecutable, firstLine);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot start FFmpeg required for mobile video processing: "
                    + ffmpegExecutable, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while checking FFmpeg availability", error);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    public TranscodeResult transcode(Path input, Path output) throws IOException, InterruptedException {
        log.info("Waiting for mobile video transcode slot: input={}, availableSlots={}",
                input, transcodeSlots.availablePermits());
        transcodeSlots.acquire();
        try {
            return transcodeExclusive(input, output);
        } finally {
            transcodeSlots.release();
        }
    }

    private TranscodeResult transcodeExclusive(Path input, Path output) throws IOException, InterruptedException {
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        Files.deleteIfExists(output);

        List<String> command = new ArrayList<>();
        command.add(ffmpegExecutable);
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("warning");
        command.add("-nostdin");
        command.add("-y");
        command.add("-filter_threads");
        command.add("1");
        command.add("-i");
        command.add(input.toString());
        command.add("-map");
        command.add("0:v:0");
        command.add("-map");
        command.add("0:a:0?");
        command.add("-vf");
        command.add("scale=w='min(1280,iw)':h='min(1280,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2,format=yuv420p");
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("veryfast");
        command.add("-crf");
        command.add("23");
        command.add("-maxrate");
        command.add("3000k");
        command.add("-bufsize");
        command.add("6000k");
        command.add("-r");
        command.add("30");
        command.add("-threads");
        command.add("2");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("128k");
        command.add("-movflags");
        command.add("+faststart");
        command.add(output.toString());

        long startedAt = System.nanoTime();
        log.info("Starting mobile video normalization: input={}, output={}, timeoutMinutes={}",
                input, output, timeout.toMinutes());

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

        StringBuilder diagnostics = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var stream = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    if (diagnostics.length() < 32_000) {
                        diagnostics.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException ignored) {
                // The process result below is authoritative.
            }
        });

        boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            reader.join(TimeUnit.SECONDS.toMillis(2));
            Files.deleteIfExists(output);
            throw new IOException("FFmpeg не успел обработать видео за " + timeout.toMinutes() + " минут");
        }
        reader.join(TimeUnit.SECONDS.toMillis(2));

        int exitCode = process.exitValue();
        if (exitCode != 0 || !Files.isRegularFile(output) || Files.size(output) <= 0) {
            Files.deleteIfExists(output);
            String details = diagnostics.toString().trim();
            if (details.length() > 1600) {
                details = details.substring(details.length() - 1600);
            }
            throw new IOException("FFmpeg завершился с кодом " + exitCode
                    + (details.isBlank() ? "" : ": " + details));
        }

        long size = Files.size(output);
        log.info("Mobile video normalization completed: input={}, output={}, bytes={}, durationMs={}",
                input, output, size, (System.nanoTime() - startedAt) / 1_000_000L);
        return new TranscodeResult(output, size, "mobile-720p-h264");
    }

    public record TranscodeResult(Path path, long size, String quality) {
    }
}
