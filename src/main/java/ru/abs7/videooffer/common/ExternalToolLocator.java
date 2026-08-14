package ru.abs7.videooffer.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Resolves command-line tools without assuming a Unix filesystem layout.
 *
 * <p>An explicit application property is tried first.  When it is blank or set
 * to {@code auto}, or when that path is unavailable, the normal OS PATH and a
 * small set of conventional installation paths are tried.  Resolution is done
 * by actually starting the tool, which works consistently on Linux and Windows
 * and avoids relying on Unix executable bits.</p>
 */
public final class ExternalToolLocator {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private ExternalToolLocator() { }

    public static Optional<ResolvedTool> resolve(
            String configuredExecutable,
            List<String> fallbackCandidates,
            List<String> versionArguments) {
        Set<String> candidates = new LinkedHashSet<>();
        String configured = normalize(configuredExecutable);
        if (configured != null && !"auto".equalsIgnoreCase(configured)) {
            candidates.add(configured);
        }
        if (fallbackCandidates != null) {
            fallbackCandidates.stream()
                    .map(ExternalToolLocator::normalize)
                    .filter(value -> value != null)
                    .forEach(candidates::add);
        }

        for (String candidate : candidates) {
            Optional<ResolvedTool> resolved = probe(candidate, versionArguments, DEFAULT_TIMEOUT);
            if (resolved.isPresent()) return resolved;
        }
        return Optional.empty();
    }

    private static Optional<ResolvedTool> probe(
            String executable,
            List<String> versionArguments,
            Duration timeout) {
        var command = new java.util.ArrayList<String>();
        command.add(executable);
        if (versionArguments != null) command.addAll(versionArguments);

        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException error) {
            return Optional.empty();
        }

        StringBuilder output = new StringBuilder();
        Thread reader = Thread.ofVirtual().name("external-tool-probe").start(() -> {
            try (var stream = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    if (output.length() < 16_000) {
                        output.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException ignored) { }
        });

        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                reader.join(500);
                return Optional.empty();
            }
            reader.join(500);
            if (process.exitValue() != 0) return Optional.empty();
            String firstLine = output.toString().lines().findFirst().orElse("available");
            return Optional.of(new ResolvedTool(executable, firstLine));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return Optional.empty();
        }
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public record ResolvedTool(String executable, String version) { }
}
