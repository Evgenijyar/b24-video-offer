package ru.abs7.videooffer.kontur;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class KonturRecordingUrlParser {
    private static final Logger log = LoggerFactory.getLogger(KonturRecordingUrlParser.class);
    private static final Pattern RECORDING_PATTERN = Pattern.compile(
            "(?:^|/)recordings/([^/?#]+)",
            Pattern.CASE_INSENSITIVE);

    public boolean isKonturRecordingUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(rawUrl.trim());
            String host = uri.getHost();
            String path = uri.getPath();
            if (host == null || path == null) {
                return false;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            boolean konturHost = "ktalk.ru".equals(normalizedHost)
                    || normalizedHost.endsWith(".ktalk.ru");
            return konturHost && RECORDING_PATTERN.matcher(path).find();
        } catch (RuntimeException error) {
            return false;
        }
    }

    public String extractRecordingKey(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            log.warn("Kontur recording URL is empty");
            throw new IllegalArgumentException("Ссылка Контур.Толка не передана");
        }

        try {
            URI uri = URI.create(rawUrl.trim());
            String path = uri.getPath();
            log.info("Parsing Kontur recording URL: scheme={}, host={}, path={}",
                    uri.getScheme(), uri.getHost(), path);

            String host = uri.getHost();
            String normalizedHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
            if (!("ktalk.ru".equals(normalizedHost) || normalizedHost.endsWith(".ktalk.ru"))) {
                throw new IllegalArgumentException("Ссылка не относится к Контур.Толку");
            }

            Matcher matcher = RECORDING_PATTERN.matcher(path == null ? "" : path);
            if (!matcher.find()) {
                log.warn("Kontur recording URL does not contain recording key: host={}, path={}",
                        uri.getHost(), path);
                throw new IllegalArgumentException("Ссылка не содержит /recordings/{key}");
            }

            String recordingKey = matcher.group(1);
            log.info("Kontur recording key extracted: recordingKey={}", recordingKey);
            return recordingKey;
        } catch (IllegalArgumentException error) {
            log.warn("Kontur recording URL parsing failed: error={}", error.getMessage());
            throw error;
        } catch (Exception error) {
            log.error("Unexpected Kontur recording URL parsing error: error={}",
                    error.getMessage(), error);
            throw new IllegalArgumentException("Некорректная ссылка Контур.Толка", error);
        }
    }
}
