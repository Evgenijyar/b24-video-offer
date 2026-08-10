package ru.abs7.videooffer.bitrix.mobile.upload;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import ru.abs7.videooffer.offer.VideoOfferResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@RestController
@RequestMapping("/bitrix/mobile/uploads")
public class MobileVideoUploadController {
    private static final Logger log = LoggerFactory.getLogger(MobileVideoUploadController.class);
    private static final int BUFFER_SIZE = 64 * 1024;

    private final MobileVideoUploadService service;

    public MobileVideoUploadController(MobileVideoUploadService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MobileVideoUploadResponse> create(
            @Valid @RequestBody CreateMobileVideoUploadRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping(value = "/{uploadId}/chunks/{sequence}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public MobileVideoUploadResponse uploadChunk(
            @PathVariable UUID uploadId,
            @PathVariable int sequence,
            @RequestHeader("X-Upload-Token") String uploadToken,
            HttpServletRequest request) throws IOException {
        return service.appendChunk(uploadId, uploadToken, sequence, request.getInputStream());
    }

    @PostMapping(value = "/{uploadId}/complete", produces = MediaType.APPLICATION_JSON_VALUE)
    public MobileVideoUploadResponse complete(
            @PathVariable UUID uploadId,
            @RequestHeader("X-Upload-Token") String uploadToken,
            @RequestParam("chunkCount") int chunkCount) throws IOException {
        return service.complete(uploadId, uploadToken, chunkCount);
    }

    @PostMapping(value = "/{uploadId}/recover", produces = MediaType.APPLICATION_JSON_VALUE)
    public MobileVideoUploadResponse recoverInterrupted(
            @PathVariable UUID uploadId,
            @RequestHeader("X-Upload-Token") String uploadToken) throws IOException {
        return service.recoverInterrupted(uploadId, uploadToken);
    }

    @GetMapping(value = "/{uploadId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public MobileVideoUploadResponse status(
            @PathVariable UUID uploadId,
            @RequestParam String uploadToken) {
        return service.status(uploadId, uploadToken);
    }

    @PostMapping(value = "/merge", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public MobileVideoUploadResponse merge(@Valid @RequestBody MergeMobileVideoUploadsRequest request)
            throws IOException, InterruptedException {
        return service.mergeSegments(request);
    }

    @DeleteMapping("/{uploadId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(
            @PathVariable UUID uploadId,
            @RequestHeader("X-Upload-Token") String uploadToken) throws IOException {
        service.discard(uploadId, uploadToken);
    }

    @PostMapping(value = "/{uploadId}/offer", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VideoOfferResponse> createOffer(
            @PathVariable UUID uploadId,
            @Valid @RequestBody CreateMobileVideoOfferRequest request) throws IOException {
        return ResponseEntity.accepted().body(service.createOffer(uploadId, request));
    }

    @GetMapping("/{uploadId}/preview")
    public void preview(
            @PathVariable UUID uploadId,
            @RequestParam String uploadToken,
            @RequestHeader(value = "Range", required = false) String rangeHeader,
            HttpServletResponse response) throws IOException {
        Path path = service.previewFile(uploadId, uploadToken);
        if (!Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Видео не найдено");
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
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"mobile-video-preview.mp4\"");
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, private");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentLengthLong(range.length());
        if (range.partial()) {
            response.setHeader(HttpHeaders.CONTENT_RANGE,
                    "bytes " + range.start() + "-" + range.end() + "/" + fileSize);
        }

        log.debug("Mobile video preview requested: uploadId={}, start={}, end={}, partial={}",
                uploadId, range.start(), range.end(), range.partial());
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r");
             OutputStream output = response.getOutputStream()) {
            file.seek(range.start());
            byte[] buffer = new byte[BUFFER_SIZE];
            long remaining = range.length();
            while (remaining > 0) {
                int read = file.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) break;
                output.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private record ByteRange(long start, long end, boolean partial) {
        long length() { return end - start + 1; }

        static ByteRange parse(String header, long fileSize) {
            if (fileSize <= 0) return null;
            if (header == null || header.isBlank() || !header.trim().startsWith("bytes=")) {
                return new ByteRange(0, fileSize - 1, false);
            }
            String value = header.trim().substring("bytes=".length()).trim();
            int comma = value.indexOf(',');
            if (comma >= 0) value = value.substring(0, comma).trim();
            int dash = value.indexOf('-');
            if (dash < 0) return null;
            String startText = value.substring(0, dash).trim();
            String endText = value.substring(dash + 1).trim();
            try {
                long start;
                long end;
                if (startText.isBlank()) {
                    if (endText.isBlank()) return null;
                    long suffixLength = Long.parseLong(endText);
                    if (suffixLength <= 0) return null;
                    start = Math.max(0, fileSize - suffixLength);
                    end = fileSize - 1;
                } else {
                    start = Long.parseLong(startText);
                    end = endText.isBlank() ? fileSize - 1 : Long.parseLong(endText);
                }
                end = Math.min(end, fileSize - 1);
                if (start < 0 || start >= fileSize || end < start) return null;
                return new ByteRange(start, end, true);
            } catch (NumberFormatException error) {
                return null;
            }
        }
    }
}
