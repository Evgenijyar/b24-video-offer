package ru.abs7.videooffer.kontur;

import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class KonturRecordingUrlParser {
    private static final Pattern RECORDING_PATTERN = Pattern.compile("(?:^|/)recordings/([^/?#]+)", Pattern.CASE_INSENSITIVE);
    public String extractRecordingKey(String rawUrl) {
        try {
            String path = URI.create(rawUrl.trim()).getPath();
            Matcher matcher = RECORDING_PATTERN.matcher(path == null ? "" : path);
            if (!matcher.find()) throw new IllegalArgumentException("Ссылка не содержит /recordings/{key}");
            return matcher.group(1);
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Некорректная ссылка Контур.Толка", e); }
    }
}
