package ru.abs7.videooffer.bitrix.mobile.upload;

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
import java.util.concurrent.TimeUnit;

@Service
public class MobileVideoMerger {
    private static final Logger log = LoggerFactory.getLogger(MobileVideoMerger.class);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration COPY_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration TRANSCODE_TIMEOUT = Duration.ofMinutes(30);

    private final String ffmpeg;
    private final String ffprobe;

    public MobileVideoMerger(
            @Value("${app.mobile-video.ffmpeg-path:/usr/bin/ffmpeg}") String ffmpeg,
            @Value("${app.mobile-video.ffprobe-path:/usr/bin/ffprobe}") String ffprobe) {
        this.ffmpeg = ffmpeg;
        this.ffprobe = ffprobe;
    }

    public MergeResult merge(List<Path> sources, Path output) throws IOException, InterruptedException {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("Нет частей видео для объединения");
        }
        if (sources.size() == 1) {
            Files.createDirectories(output.toAbsolutePath().normalize().getParent());
            Files.copy(sources.getFirst(), output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return new MergeResult(output, Files.size(output), "single-segment-copy");
        }

        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        Files.deleteIfExists(output);
        List<SegmentInfo> infos = new ArrayList<>();
        for (Path source : sources) {
            if (!Files.isRegularFile(source)) {
                throw new IllegalArgumentException("Часть записи не найдена: " + source.getFileName());
            }
            infos.add(probe(source));
        }

        Path workDir = output.resolveSibling(output.getFileName() + ".merge-work");
        Files.createDirectories(workDir);
        List<Path> prepared = new ArrayList<>();
        try {
            for (int i = 0; i < sources.size(); i++) {
                Path source = sources.get(i);
                SegmentInfo info = infos.get(i);
                if (info.hasAudio()) {
                    prepared.add(source);
                } else {
                    Path withSilence = workDir.resolve(String.format(Locale.ROOT, "segment-%03d-silence.mp4", i));
                    addSilentAudio(source, withSilence);
                    prepared.add(withSilence);
                    infos.set(i, new SegmentInfo(info.videoCodec(), "aac", info.width(), info.height()));
                }
            }

            if (canFastConcat(infos)) {
                try {
                    long startedAt = System.nanoTime();
                    fastConcat(prepared, output, workDir);
                    long bytes = Files.size(output);
                    log.info("Mobile video segments fast-concat completed: segments={}, bytes={}, durationMs={}",
                            sources.size(), bytes, elapsedMillis(startedAt));
                    return new MergeResult(output, bytes, "segments-fast-concat");
                } catch (IOException error) {
                    Files.deleteIfExists(output);
                    log.warn("Fast segment concat failed, falling back to normalized concat: segments={}, error={}",
                            sources.size(), error.getMessage());
                }
            }

            long startedAt = System.nanoTime();
            transcodeConcat(prepared, output);
            long bytes = Files.size(output);
            log.info("Mobile video segments normalized-concat completed: segments={}, bytes={}, durationMs={}",
                    sources.size(), bytes, elapsedMillis(startedAt));
            return new MergeResult(output, bytes, "segments-normalized-concat");
        } finally {
            deleteTree(workDir);
        }
    }

    private SegmentInfo probe(Path source) throws IOException, InterruptedException {
        List<String> command = List.of(
                ffprobe, "-v", "error",
                "-show_entries", "stream=codec_type,codec_name,width,height",
                "-of", "csv=p=0",
                source.toString());
        ProcessResult result = run(command, PROBE_TIMEOUT);
        if (result.exitCode() != 0) {
            throw new IOException("FFprobe не смог прочитать часть записи" + suffix(result.output()));
        }
        String videoCodec = null;
        String audioCodec = null;
        int width = 0;
        int height = 0;
        for (String raw : result.output().lines().toList()) {
            String line = raw.trim();
            if (line.isBlank()) continue;
            String[] parts = line.split(",", -1);
            if (parts.length < 2) continue;
            String codec = parts[0].trim();
            String type = parts[1].trim();
            // Depending on ffprobe build CSV may output codec_type first. Handle both orders.
            if ("video".equals(codec) || "audio".equals(codec)) {
                String swap = codec;
                codec = type;
                type = swap;
            }
            if ("video".equals(type)) {
                videoCodec = codec;
                if (parts.length >= 4) {
                    width = parseInt(parts[2]);
                    height = parseInt(parts[3]);
                }
            } else if ("audio".equals(type)) {
                audioCodec = codec;
            }
        }
        if (videoCodec == null) {
            // Reliable fallback parser if CSV order differs from expectations.
            ProcessResult fallback = run(List.of(
                    ffprobe, "-v", "error", "-select_streams", "v:0",
                    "-show_entries", "stream=codec_name,width,height", "-of", "csv=p=0",
                    source.toString()), PROBE_TIMEOUT);
            String[] parts = fallback.output().trim().split(",");
            if (fallback.exitCode() == 0 && parts.length >= 3) {
                videoCodec = parts[0].trim();
                width = parseInt(parts[1]);
                height = parseInt(parts[2]);
            }
            ProcessResult audio = run(List.of(
                    ffprobe, "-v", "error", "-select_streams", "a:0",
                    "-show_entries", "stream=codec_name", "-of", "csv=p=0",
                    source.toString()), PROBE_TIMEOUT);
            if (audio.exitCode() == 0 && !audio.output().trim().isBlank()) {
                audioCodec = audio.output().trim().lines().findFirst().orElse("").trim();
            }
        }
        if (videoCodec == null || width <= 0 || height <= 0) {
            throw new IOException("Не удалось определить параметры части записи");
        }
        return new SegmentInfo(videoCodec, audioCodec, width, height);
    }

    private void addSilentAudio(Path input, Path output) throws IOException, InterruptedException {
        List<String> command = List.of(
                ffmpeg, "-hide_banner", "-loglevel", "warning", "-nostdin", "-y",
                "-i", input.toString(),
                "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo",
                "-map", "0:v:0", "-map", "1:a:0",
                "-c:v", "copy", "-c:a", "aac", "-b:a", "112k",
                "-shortest", "-movflags", "+faststart", output.toString());
        ProcessResult result = run(command, COPY_TIMEOUT);
        if (result.exitCode() != 0 || !Files.isRegularFile(output) || Files.size(output) <= 0) {
            Files.deleteIfExists(output);
            throw new IOException("Не удалось подготовить беззвучную часть записи" + suffix(result.output()));
        }
    }

    private boolean canFastConcat(List<SegmentInfo> infos) {
        SegmentInfo first = infos.getFirst();
        for (SegmentInfo info : infos) {
            if (!"h264".equalsIgnoreCase(info.videoCodec())
                    || !"aac".equalsIgnoreCase(info.audioCodec())
                    || info.width() != first.width()
                    || info.height() != first.height()) {
                return false;
            }
        }
        return true;
    }

    private void fastConcat(List<Path> sources, Path output, Path workDir) throws IOException, InterruptedException {
        Path list = workDir.resolve("concat.txt");
        StringBuilder content = new StringBuilder();
        for (Path source : sources) {
            content.append("file '").append(source.toAbsolutePath().normalize().toString().replace("'", "'\\''"))
                    .append("'\n");
        }
        Files.writeString(list, content.toString(), StandardCharsets.UTF_8);
        List<String> command = List.of(
                ffmpeg, "-hide_banner", "-loglevel", "warning", "-nostdin", "-y",
                "-f", "concat", "-safe", "0", "-i", list.toString(),
                "-map", "0:v:0", "-map", "0:a:0?", "-c", "copy",
                "-movflags", "+faststart", output.toString());
        ProcessResult result = run(command, COPY_TIMEOUT);
        if (result.exitCode() != 0 || !Files.isRegularFile(output) || Files.size(output) <= 0) {
            Files.deleteIfExists(output);
            throw new IOException("FFmpeg не смог быстро объединить части записи" + suffix(result.output()));
        }
    }

    private void transcodeConcat(List<Path> sources, Path output) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(ffmpeg);
        command.addAll(List.of("-hide_banner", "-loglevel", "warning", "-nostdin", "-y"));
        for (Path source : sources) {
            command.add("-i");
            command.add(source.toString());
        }
        StringBuilder filter = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            filter.append('[').append(i).append(":v:0]")
                    .append("scale=1280:720:force_original_aspect_ratio=decrease,")
                    .append("pad=1280:720:(ow-iw)/2:(oh-ih)/2:color=black,")
                    .append("setsar=1,format=yuv420p[v").append(i).append("]; ")
                    .append('[').append(i).append(":a:0]")
                    .append("aformat=sample_rates=48000:channel_layouts=stereo,")
                    .append("aresample=48000,asetpts=PTS-STARTPTS[a").append(i).append("]; ");
        }
        for (int i = 0; i < sources.size(); i++) {
            filter.append("[v").append(i).append("][a").append(i).append(']');
        }
        filter.append("concat=n=").append(sources.size()).append(":v=1:a=1[outv][outa]");

        command.addAll(List.of(
                "-filter_complex", filter.toString(),
                "-map", "[outv]", "-map", "[outa]",
                "-c:v", "libx264", "-preset", "ultrafast", "-crf", "20", "-threads", "0",
                "-c:a", "aac", "-b:a", "112k", "-movflags", "+faststart",
                output.toString()));
        ProcessResult result = run(command, TRANSCODE_TIMEOUT);
        if (result.exitCode() != 0 || !Files.isRegularFile(output) || Files.size(output) <= 0) {
            Files.deleteIfExists(output);
            throw new IOException("FFmpeg не смог объединить части записи" + suffix(result.output()));
        }
    }

    private ProcessResult run(List<String> command, Duration timeout) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var input = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (output.length() < 64_000) {
                        output.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException ignored) { }
        });
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        reader.join(2000);
        return new ProcessResult(finished ? process.exitValue() : -1, output.toString());
    }

    private int parseInt(String value) {
        try { return Integer.parseInt(value.trim()); } catch (Exception ignored) { return 0; }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private String suffix(String value) {
        String details = value == null ? "" : value.trim();
        if (details.length() > 2500) details = details.substring(details.length() - 2500);
        return details.isBlank() ? "" : ": " + details;
    }

    private void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private record SegmentInfo(String videoCodec, String audioCodec, int width, int height) {
        boolean hasAudio() { return audioCodec != null && !audioCodec.isBlank(); }
    }

    private record ProcessResult(int exitCode, String output) { }
    public record MergeResult(Path path, long size, String quality) { }
}
