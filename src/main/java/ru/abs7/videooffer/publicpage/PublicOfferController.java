package ru.abs7.videooffer.publicpage;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.abs7.videooffer.offer.VideoOffer;
import ru.abs7.videooffer.offer.VideoOfferService;
import ru.abs7.videooffer.offer.VideoOfferStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
public class PublicOfferController {
    private static final Logger log = LoggerFactory.getLogger(PublicOfferController.class);
    private static final int BUFFER_SIZE = 64 * 1024;

    private final VideoOfferService service;

    public PublicOfferController(VideoOfferService service) {
        this.service = service;
    }

    @GetMapping(value = "/o/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public byte[] page(@PathVariable String token) throws IOException {
        VideoOffer offer = service.getByToken(token);
        log.info("Public offer page requested: offerId={}, status={}", offer.getId(), offer.getStatus());
        try (InputStream input = new ClassPathResource("static/offer.html").getInputStream()) {
            return input.readAllBytes();
        }
    }

    @GetMapping("/api/public/offers/{token}")
    public PublicOfferResponse data(@PathVariable String token) {
        VideoOffer offer = service.getByToken(token);
        log.info("Public offer data requested: offerId={}, status={}, progress={}%",
                offer.getId(), offer.getStatus(), offer.getProgressPercent());
        return PublicOfferResponse.from(offer);
    }

    @GetMapping("/media/{token}")
    public void media(
            @PathVariable String token,
            @RequestHeader(value = "Range", required = false) String rangeHeader,
            HttpServletResponse response) throws IOException {
        VideoOffer offer = service.getByToken(token);
        if (offer.getStatus() != VideoOfferStatus.READY || offer.getVideoFilePath() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Видео ещё не готово");
        }

        Path path = Path.of(offer.getVideoFilePath());
        log.info("Public video stream requested: offerId={}, rangeHeader={}, file={}",
                offer.getId(), rangeHeader, path);
        if (!Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Видео отсутствует на сервере");
        }

        long fileSize = Files.size(path);
        ByteRange range = ByteRange.parse(rangeHeader, fileSize);
        if (range == null) {
            response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + fileSize);
            return;
        }

        response.reset();
        response.setStatus(range.partial()
                ? HttpServletResponse.SC_PARTIAL_CONTENT
                : HttpServletResponse.SC_OK);
        response.setContentType("video/mp4");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"video-offer.mp4\"");
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=3600, no-transform");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentLengthLong(range.length());
        if (range.partial()) {
            response.setHeader(
                    HttpHeaders.CONTENT_RANGE,
                    "bytes " + range.start() + "-" + range.end() + "/" + fileSize);
        }

        long streamStartedAt = System.nanoTime();
        log.info("Public video stream started: offerId={}, start={}, end={}, length={}, partial={}",
                offer.getId(), range.start(), range.end(), range.length(), range.partial());
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r");
             OutputStream output = response.getOutputStream()) {
            file.seek(range.start());
            byte[] buffer = new byte[BUFFER_SIZE];
            long remaining = range.length();
            while (remaining > 0) {
                int read = file.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
        }
        log.info("Public video stream completed: offerId={}, length={}, durationMs={}",
                offer.getId(), range.length(), (System.nanoTime() - streamStartedAt) / 1_000_000L);
    }

    private record ByteRange(long start, long end, boolean partial) {
        long length() {
            return end - start + 1;
        }

        static ByteRange parse(String header, long fileSize) {
            if (fileSize <= 0) {
                return null;
            }
            if (header == null || header.isBlank() || !header.trim().startsWith("bytes=")) {
                return new ByteRange(0, fileSize - 1, false);
            }

            String value = header.trim().substring("bytes=".length()).trim();
            int comma = value.indexOf(',');
            if (comma >= 0) {
                value = value.substring(0, comma).trim();
            }
            int dash = value.indexOf('-');
            if (dash < 0) {
                return null;
            }

            String startText = value.substring(0, dash).trim();
            String endText = value.substring(dash + 1).trim();
            try {
                long start;
                long end;
                if (startText.isBlank()) {
                    if (endText.isBlank()) {
                        return null;
                    }
                    long suffixLength = Long.parseLong(endText);
                    if (suffixLength <= 0) {
                        return null;
                    }
                    start = Math.max(0, fileSize - suffixLength);
                    end = fileSize - 1;
                } else {
                    start = Long.parseLong(startText);
                    end = endText.isBlank() ? fileSize - 1 : Long.parseLong(endText);
                }

                end = Math.min(end, fileSize - 1);
                if (start < 0 || start >= fileSize || end < start) {
                    return null;
                }
                return new ByteRange(start, end, true);
            } catch (NumberFormatException error) {
                return null;
            }
        }
    }
}
