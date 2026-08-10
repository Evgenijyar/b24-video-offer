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
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class MobileVideoTranscoder {
    private static final Logger log = LoggerFactory.getLogger(MobileVideoTranscoder.class);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration REMUX_TIMEOUT = Duration.ofMinutes(2);

    private final Duration timeout;
    private final Semaphore transcodeSlots;
    private final String ffmpegExecutable;
    private final String ffprobeExecutable;

    public MobileVideoTranscoder(
            @Value("${app.mobile-video.ffmpeg-timeout-minutes:20}") long timeoutMinutes,
            @Value("${app.mobile-video.max-concurrent-transcodes:1}") int maxConcurrentTranscodes,
            @Value("${app.mobile-video.ffmpeg-path:/usr/bin/ffmpeg}") String ffmpegExecutable,
            @Value("${app.mobile-video.ffprobe-path:/usr/bin/ffprobe}") String ffprobeExecutable) {
        this.timeout = Duration.ofMinutes(Math.max(2, timeoutMinutes));
        this.transcodeSlots = new Semaphore(Math.max(1, Math.min(4, maxConcurrentTranscodes)), true);
        this.ffmpegExecutable = normalizeExecutable(ffmpegExecutable, "/usr/bin/ffmpeg");
        this.ffprobeExecutable = normalizeExecutable(ffprobeExecutable, "/usr/bin/ffprobe");
    }

    @PostConstruct
    void verifyMediaToolsAvailable() {
        String ffmpegVersion = verifyExecutable(ffmpegExecutable, "FFmpeg");
        String ffprobeVersion = verifyExecutable(ffprobeExecutable, "FFprobe");
        log.info("Mobile media tools ready: ffmpeg={}, ffprobe={}, ffmpegVersion={}, ffprobeVersion={}",
                ffmpegExecutable, ffprobeExecutable, ffmpegVersion, ffprobeVersion);
    }

    public TranscodeResult transcode(Path input, Path output, String declaredMimeType)
            throws IOException, InterruptedException {
        log.info("Waiting for mobile video processing slot: input={}, availableSlots={}",
                input, transcodeSlots.availablePermits());
        transcodeSlots.acquire();
        try {
            return processExclusive(input, output, declaredMimeType);
        } finally {
            transcodeSlots.release();
        }
    }

    private TranscodeResult processExclusive(Path input, Path output, String declaredMimeType)
            throws IOException, InterruptedException {
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        Files.deleteIfExists(output);

        ProbeResult probe = probe(input);
        log.info("Mobile video probe completed: input={}, declaredMimeType={}, format={}, videoCodec={}, audioCodec={}, width={}, height={}",
                input, declaredMimeType, probe.formatName(), probe.videoCodec(), probe.audioCodec(), probe.width(), probe.height());

        if (canRemuxWithoutReencoding(probe, declaredMimeType)) {
            long startedAt = System.nanoTime();
            try {
                runFfmpeg(remuxCommand(input, output), REMUX_TIMEOUT, output, "быстрый MP4 remux");
                long size = Files.size(output);
                log.info("Mobile video fast remux completed: input={}, output={}, bytes={}, durationMs={}",
                        input, output, size, elapsedMillis(startedAt));
                return new TranscodeResult(output, size, "mobile-h264-direct");
            } catch (IOException error) {
                Files.deleteIfExists(output);
                log.warn("Fast mobile MP4 remux failed; falling back to full normalization: input={}, error={}",
                        input, error.getMessage());
            }
        }

        long startedAt = System.nanoTime();
        log.info("Starting full mobile video normalization: input={}, output={}, timeoutMinutes={}",
                input, output, timeout.toMinutes());
        runFfmpeg(fullTranscodeCommand(input, output), timeout, output, "полная обработка видео");
        long size = Files.size(output);
        log.info("Full mobile video normalization completed: input={}, output={}, bytes={}, durationMs={}",
                input, output, size, elapsedMillis(startedAt));
        return new TranscodeResult(output, size, "mobile-720p-h264");
    }

    private ProbeResult probe(Path input) throws IOException, InterruptedException {
        List<String> command = List.of(
                ffprobeExecutable,
                "-v", "error",
                "-show_entries", "stream=codec_type,codec_name,width,height:format=format_name",
                "-of", "default=noprint_wrappers=0",
                input.toString());
        ProcessResult result = runProcess(command, PROBE_TIMEOUT);
        if (result.exitCode() != 0) {
            throw new IOException("FFprobe завершился с кодом " + result.exitCode()
                    + diagnosticSuffix(result.output()));
        }

        String format = null;
        String videoCodec = null;
        String audioCodec = null;
        int width = 0;
        int height = 0;
        String section = "";
        String currentType = null;
        String currentCodec = null;
        int currentWidth = 0;
        int currentHeight = 0;

        for (String rawLine : result.output().lines().toList()) {
            String line = rawLine.trim();
            if ("[STREAM]".equals(line)) {
                section = "STREAM";
                currentType = null;
                currentCodec = null;
                currentWidth = 0;
                currentHeight = 0;
                continue;
            }
            if ("[/STREAM]".equals(line)) {
                if ("video".equalsIgnoreCase(currentType) && videoCodec == null) {
                    videoCodec = currentCodec;
                    width = currentWidth;
                    height = currentHeight;
                } else if ("audio".equalsIgnoreCase(currentType) && audioCodec == null) {
                    audioCodec = currentCodec;
                }
                section = "";
                continue;
            }
            if ("[FORMAT]".equals(line)) {
                section = "FORMAT";
                continue;
            }
            if ("[/FORMAT]".equals(line)) {
                section = "";
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) continue;
            String key = line.substring(0, equals);
            String value = line.substring(equals + 1);
            if ("STREAM".equals(section)) {
                switch (key) {
                    case "codec_type" -> currentType = value;
                    case "codec_name" -> currentCodec = value;
                    case "width" -> currentWidth = parseInt(value);
                    case "height" -> currentHeight = parseInt(value);
                    default -> { }
                }
            } else if ("FORMAT".equals(section) && "format_name".equals(key)) {
                format = value;
            }
        }

        if (videoCodec == null || videoCodec.isBlank()) {
            throw new IOException("В загруженном файле не найден видеопоток");
        }
        return new ProbeResult(format, videoCodec, audioCodec, width, height);
    }

    private boolean canRemuxWithoutReencoding(ProbeResult probe, String declaredMimeType) {
        String mime = declaredMimeType == null ? "" : declaredMimeType.toLowerCase(Locale.ROOT);
        String format = probe.formatName() == null ? "" : probe.formatName().toLowerCase(Locale.ROOT);
        boolean mp4Container = mime.startsWith("video/mp4")
                || format.contains("mp4")
                || format.contains("mov");
        boolean compatibleVideo = "h264".equalsIgnoreCase(probe.videoCodec());
        boolean compatibleAudio = probe.audioCodec() == null || "aac".equalsIgnoreCase(probe.audioCodec());
        int maxDimension = Math.max(probe.width(), probe.height());
        boolean sizeOkay = maxDimension > 0 && maxDimension <= 1280;
        return mp4Container && compatibleVideo && compatibleAudio && sizeOkay;
    }

    private List<String> remuxCommand(Path input, Path output) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegExecutable);
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("warning");
        command.add("-nostdin");
        command.add("-y");
        command.add("-i");
        command.add(input.toString());
        command.add("-map");
        command.add("0:v:0");
        command.add("-map");
        command.add("0:a:0?");
        command.add("-c");
        command.add("copy");
        command.add("-movflags");
        command.add("+faststart");
        command.add(output.toString());
        return command;
    }

    private List<String> fullTranscodeCommand(Path input, Path output) {
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
        command.add("2600k");
        command.add("-bufsize");
        command.add("5200k");
        command.add("-r");
        command.add("30");
        command.add("-threads");
        command.add("2");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("112k");
        command.add("-movflags");
        command.add("+faststart");
        command.add(output.toString());
        return command;
    }

    private void runFfmpeg(List<String> command, Duration processTimeout, Path output, String operation)
            throws IOException, InterruptedException {
        ProcessResult result = runProcess(command, processTimeout);
        if (result.exitCode() != 0 || !Files.isRegularFile(output) || Files.size(output) <= 0) {
            Files.deleteIfExists(output);
            throw new IOException("FFmpeg: " + operation + " завершилась с кодом " + result.exitCode()
                    + diagnosticSuffix(result.output()));
        }
    }

    private ProcessResult runProcess(List<String> command, Duration processTimeout)
            throws IOException, InterruptedException {
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
                // Process exit status below is authoritative.
            }
        });

        boolean finished = process.waitFor(processTimeout.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            reader.join(TimeUnit.SECONDS.toMillis(2));
            throw new IOException("Обработка видео превысила лимит времени " + processTimeout.toSeconds() + " секунд");
        }
        reader.join(TimeUnit.SECONDS.toMillis(2));
        return new ProcessResult(process.exitValue(), diagnostics.toString());
    }

    private String verifyExecutable(String executable, String label) {
        Path path = Path.of(executable);
        if (!Files.isRegularFile(path) || !Files.isExecutable(path)) {
            throw new IllegalStateException(label + " is required for mobile video processing but is not executable: "
                    + executable);
        }
        try {
            ProcessResult result = runProcess(List.of(executable, "-version"), Duration.ofSeconds(5));
            if (result.exitCode() != 0) {
                throw new IllegalStateException(label + " availability check failed with code " + result.exitCode());
            }
            return result.output().lines().findFirst().orElse(label + " available");
        } catch (IOException error) {
            throw new IllegalStateException("Cannot start " + label + " required for mobile video processing: "
                    + executable, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while checking " + label + " availability", error);
        }
    }

    private String normalizeExecutable(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String diagnosticSuffix(String output) {
        String details = output == null ? "" : output.trim();
        if (details.length() > 1600) {
            details = details.substring(details.length() - 1600);
        }
        return details.isBlank() ? "" : ": " + details;
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private record ProbeResult(
            String formatName,
            String videoCodec,
            String audioCodec,
            int width,
            int height) {
    }

    private record ProcessResult(int exitCode, String output) {
    }

    public record TranscodeResult(Path path, long size, String quality) {
    }
}
