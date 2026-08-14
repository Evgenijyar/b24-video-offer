package ru.abs7.videooffer.bitrix.mobile.upload;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.abs7.videooffer.common.ExternalToolLocator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

@Service
public class MobileVideoTranscoder {
    private static final Logger log = LoggerFactory.getLogger(MobileVideoTranscoder.class);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration REMUX_TIMEOUT = Duration.ofMinutes(2);

    private final Duration timeout;
    private final Semaphore transcodeSlots;
    private final String configuredFfmpegExecutable;
    private final String configuredFfprobeExecutable;
    private volatile String ffmpegExecutable;
    private volatile String ffprobeExecutable;
    private volatile String mediaToolsUnavailableReason;

    public MobileVideoTranscoder(
            @Value("${app.mobile-video.ffmpeg-timeout-minutes:20}") long timeoutMinutes,
            @Value("${app.mobile-video.max-concurrent-transcodes:1}") int maxConcurrentTranscodes,
            @Value("${app.mobile-video.ffmpeg-path:auto}") String ffmpegExecutable,
            @Value("${app.mobile-video.ffprobe-path:auto}") String ffprobeExecutable) {
        this.timeout = Duration.ofMinutes(Math.max(2, timeoutMinutes));
        this.transcodeSlots = new Semaphore(Math.max(1, Math.min(4, maxConcurrentTranscodes)), true);
        this.configuredFfmpegExecutable = normalizeExecutable(ffmpegExecutable, "auto");
        this.configuredFfprobeExecutable = normalizeExecutable(ffprobeExecutable, "auto");
    }

    @PostConstruct
    void verifyMediaToolsAvailable() {
        var ffmpeg = ExternalToolLocator.resolve(
                configuredFfmpegExecutable,
                List.of("ffmpeg", "ffmpeg.exe", "/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg"),
                List.of("-version"));
        var ffprobe = ExternalToolLocator.resolve(
                configuredFfprobeExecutable,
                List.of("ffprobe", "ffprobe.exe", "/usr/bin/ffprobe", "/usr/local/bin/ffprobe"),
                List.of("-version"));

        if (ffmpeg.isPresent() && ffprobe.isPresent()) {
            this.ffmpegExecutable = ffmpeg.get().executable();
            this.ffprobeExecutable = ffprobe.get().executable();
            this.mediaToolsUnavailableReason = null;
            log.info("Mobile media tools ready: ffmpeg={}, ffprobe={}, ffmpegVersion={}, ffprobeVersion={}",
                    ffmpegExecutable, ffprobeExecutable, ffmpeg.get().version(), ffprobe.get().version());
            return;
        }

        this.ffmpegExecutable = ffmpeg.map(ExternalToolLocator.ResolvedTool::executable).orElse(null);
        this.ffprobeExecutable = ffprobe.map(ExternalToolLocator.ResolvedTool::executable).orElse(null);
        this.mediaToolsUnavailableReason = "FFmpeg/FFprobe are not available on this machine";
        log.warn("Mobile media tools are unavailable. Application startup will continue; video import/transcoding "
                        + "will be unavailable until FFmpeg and FFprobe are installed or app.mobile-video.*-path is configured. "
                        + "configuredFfmpeg={}, configuredFfprobe={}, ffmpegResolved={}, ffprobeResolved={}",
                configuredFfmpegExecutable, configuredFfprobeExecutable, ffmpegExecutable, ffprobeExecutable);
    }

    public TranscodeResult transcode(Path input, Path output, String declaredMimeType)
            throws IOException, InterruptedException {
        return transcode(input, output, declaredMimeType, ignored -> { });
    }

    /**
     * Normalizes a video only when this is required for reliable browser playback.
     * progressConsumer receives real media-processing progress from 0 to 100.
     */
    public TranscodeResult transcode(
            Path input,
            Path output,
            String declaredMimeType,
            IntConsumer progressConsumer) throws IOException, InterruptedException {
        IntConsumer progress = progressConsumer == null ? ignored -> { } : progressConsumer;
        requireMediaToolsAvailable();
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        Files.deleteIfExists(output);

        progress.accept(1);
        long probeStartedAt = System.nanoTime();
        ProbeResult probe = probe(input);
        log.info("Mobile video probe completed: input={}, declaredMimeType={}, format={}, videoCodec={}, audioCodec={}, width={}, height={}, durationSeconds={}, durationMs={}",
                input, declaredMimeType, probe.formatName(), probe.videoCodec(), probe.audioCodec(), probe.width(), probe.height(),
                probe.durationSeconds(), elapsedMillis(probeStartedAt));

        // The public player already consumes MP4/H.264/AAC directly. This is the fast path
        // for the vast majority of phone/desktop files and modern MediaRecorder output.
        if (canUseMp4AsIs(probe, declaredMimeType)) {
            long size = Files.size(input);
            progress.accept(100);
            log.info("Mobile video accepted without FFmpeg: input={}, bytes={}, quality=mp4-h264-as-is", input, size);
            return new TranscodeResult(input, size, "mp4-h264-as-is");
        }

        // H.264 never needs to be re-encoded merely because of its container or audio codec.
        // Copy the video bit-for-bit and only convert audio to AAC when needed.
        if ("h264".equalsIgnoreCase(probe.videoCodec())) {
            long startedAt = System.nanoTime();
            runFfmpeg(h264CopyCommand(input, output, probe), REMUX_TIMEOUT, output, "быстрая упаковка H.264 в MP4");
            long size = Files.size(output);
            progress.accept(100);
            log.info("Mobile H.264 fast packaging completed: input={}, output={}, bytes={}, durationMs={}",
                    input, output, size, elapsedMillis(startedAt));
            return new TranscodeResult(output, size,
                    "aac".equalsIgnoreCase(probe.audioCodec()) || probe.audioCodec() == null
                            ? "mp4-h264-remux" : "mp4-h264-audio-normalized");
        }

        log.info("Waiting for full video transcode slot: input={}, availableSlots={}",
                input, transcodeSlots.availablePermits());
        transcodeSlots.acquire();
        try {
            long startedAt = System.nanoTime();
            log.info("Starting full mobile video normalization: input={}, output={}, timeoutMinutes={}",
                    input, output, timeout.toMinutes());
            runFfmpegWithProgress(fullTranscodeCommand(input, output), timeout, output,
                    "полная обработка видео", probe.durationSeconds(), progress);
            long size = Files.size(output);
            progress.accept(100);
            log.info("Full mobile video normalization completed: input={}, output={}, bytes={}, durationMs={}",
                    input, output, size, elapsedMillis(startedAt));
            return new TranscodeResult(output, size, "mobile-h264-normalized");
        } finally {
            transcodeSlots.release();
        }
    }

    private boolean canUseMp4AsIs(ProbeResult probe, String declaredMimeType) {
        String mime = declaredMimeType == null ? "" : declaredMimeType.toLowerCase(Locale.ROOT);
        String format = probe.formatName() == null ? "" : probe.formatName().toLowerCase(Locale.ROOT);
        boolean mp4Mime = mime.startsWith("video/mp4")
                || mime.startsWith("video/x-m4v")
                || "application/octet-stream".equals(mime);
        boolean mp4Container = format.contains("mp4") || format.contains("m4v");
        boolean compatibleVideo = "h264".equalsIgnoreCase(probe.videoCodec());
        boolean compatibleAudio = probe.audioCodec() == null || "aac".equalsIgnoreCase(probe.audioCodec());
        return mp4Mime && mp4Container && compatibleVideo && compatibleAudio;
    }

    private ProbeResult probe(Path input) throws IOException, InterruptedException {
        List<String> command = List.of(
                ffprobeExecutable,
                "-v", "error",
                "-show_entries", "stream=codec_type,codec_name,width,height:format=format_name,duration",
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
        double durationSeconds = 0;
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
            if ("[FORMAT]".equals(line)) { section = "FORMAT"; continue; }
            if ("[/FORMAT]".equals(line)) { section = ""; continue; }
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
            } else if ("FORMAT".equals(section)) {
                if ("format_name".equals(key)) format = value;
                else if ("duration".equals(key)) durationSeconds = parseDouble(value);
            }
        }

        if (videoCodec == null || videoCodec.isBlank()) {
            throw new IOException("В загруженном файле не найден видеопоток");
        }
        return new ProbeResult(format, videoCodec, audioCodec, width, height, durationSeconds);
    }

    private List<String> h264CopyCommand(Path input, Path output, ProbeResult probe) {
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
        command.add("-c:v");
        command.add("copy");
        if (probe.audioCodec() == null || "aac".equalsIgnoreCase(probe.audioCodec())) {
            command.add("-c:a");
            command.add("copy");
        } else {
            command.add("-c:a");
            command.add("aac");
            command.add("-b:a");
            command.add("128k");
        }
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
        command.add("ultrafast");
        command.add("-crf");
        command.add("20");
        command.add("-threads");
        command.add("0");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("128k");
        command.add("-movflags");
        command.add("+faststart");
        command.add("-progress");
        command.add("pipe:1");
        command.add("-nostats");
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

    private void runFfmpegWithProgress(
            List<String> command,
            Duration processTimeout,
            Path output,
            String operation,
            double durationSeconds,
            IntConsumer progressConsumer) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder diagnostics = new StringBuilder();
        AtomicInteger lastProgress = new AtomicInteger(1);

        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    if (diagnostics.length() < 32_000) diagnostics.append(line).append('\n');
                    int equals = line.indexOf('=');
                    if (equals <= 0 || durationSeconds <= 0) continue;
                    String key = line.substring(0, equals).trim();
                    String value = line.substring(equals + 1).trim();
                    double outSeconds = 0;
                    if ("out_time_us".equals(key) || "out_time_ms".equals(key)) {
                        outSeconds = parseLong(value) / 1_000_000.0;
                    } else if ("out_time".equals(key)) {
                        outSeconds = parseClockSeconds(value);
                    }
                    if (outSeconds <= 0) continue;
                    int percent = Math.max(1, Math.min(99, (int) Math.floor(outSeconds * 100.0 / durationSeconds)));
                    int previous = lastProgress.getAndUpdate(old -> Math.max(old, percent));
                    if (percent > previous) {
                        try { progressConsumer.accept(percent); } catch (RuntimeException ignored) { }
                    }
                }
            } catch (IOException ignored) {
                // Exit status below is authoritative.
            }
        });

        boolean finished = process.waitFor(processTimeout.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            reader.join(TimeUnit.SECONDS.toMillis(2));
            Files.deleteIfExists(output);
            throw new IOException("Обработка видео превысила лимит времени " + processTimeout.toSeconds() + " секунд");
        }
        reader.join(TimeUnit.SECONDS.toMillis(2));
        if (process.exitValue() != 0 || !Files.isRegularFile(output) || Files.size(output) <= 0) {
            Files.deleteIfExists(output);
            throw new IOException("FFmpeg: " + operation + " завершилась с кодом " + process.exitValue()
                    + diagnosticSuffix(diagnostics.toString()));
        }
    }

    private ProcessResult runProcess(List<String> command, Duration processTimeout)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
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
            } catch (IOException ignored) { }
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

    private void requireMediaToolsAvailable() {
        if (ffmpegExecutable != null && ffprobeExecutable != null) return;
        throw new IllegalStateException(mediaToolsUnavailableReason == null
                ? "FFmpeg/FFprobe are required for video processing"
                : mediaToolsUnavailableReason);
    }

    private String normalizeExecutable(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String diagnosticSuffix(String output) {
        String details = output == null ? "" : output.trim();
        if (details.length() > 1600) details = details.substring(details.length() - 1600);
        return details.isBlank() ? "" : ": " + details;
    }

    private int parseInt(String value) {
        try { return Integer.parseInt(value); } catch (NumberFormatException error) { return 0; }
    }

    private long parseLong(String value) {
        try { return Long.parseLong(value); } catch (NumberFormatException error) { return 0; }
    }

    private double parseDouble(String value) {
        try { return Double.parseDouble(value); } catch (NumberFormatException error) { return 0; }
    }

    private double parseClockSeconds(String value) {
        try {
            String[] parts = value.split(":");
            if (parts.length != 3) return 0;
            return Double.parseDouble(parts[0]) * 3600 + Double.parseDouble(parts[1]) * 60 + Double.parseDouble(parts[2]);
        } catch (RuntimeException error) { return 0; }
    }

    private long elapsedMillis(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000L; }

    private record ProbeResult(
            String formatName,
            String videoCodec,
            String audioCodec,
            int width,
            int height,
            double durationSeconds) { }

    private record ProcessResult(int exitCode, String output) { }

    public record TranscodeResult(Path path, long size, String quality) { }
}
