package ru.abs7.videooffer.bitrix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.abs7.videooffer.offer.CrmEntityType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

@Service
public class BitrixContextSigner {
    private static final Logger log = LoggerFactory.getLogger(BitrixContextSigner.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String VERSION = "v3";
    private final byte[] secret;

    public BitrixContextSigner(BitrixProperties properties) {
        if (properties.clientSecret() == null || properties.clientSecret().isBlank()) {
            throw new IllegalStateException("app.bitrix.client-secret не настроен");
        }
        this.secret = properties.clientSecret().getBytes(StandardCharsets.UTF_8);
    }

    public String create(BitrixPlacementContext context) {
        String payload = VERSION + "|"
                + nullLong(context.tenantId()) + "|"
                + safe(context.memberId()) + "|"
                + nullLong(context.bitrixUserId()) + "|"
                + context.entityType().name() + "|"
                + context.entityId();
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String token = encode(payloadBytes) + "." + encode(sign(payloadBytes));
        log.info("Bitrix context signed: tenantId={}, memberId={}, userId={}, entityType={}, entityId={}, version={}",
                context.tenantId(), context.memberId(), context.bitrixUserId(), context.entityType(), context.entityId(), VERSION);
        return token;
    }

    public BitrixPlacementContext verify(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Контекст Bitrix24 не передан");
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) throw new IllegalArgumentException("Некорректный контекст Bitrix24");
        byte[] payloadBytes;
        byte[] suppliedSignature;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(parts[0]);
            suppliedSignature = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Некорректный контекст Bitrix24", error);
        }
        if (!MessageDigest.isEqual(sign(payloadBytes), suppliedSignature)) {
            throw new IllegalArgumentException("Подпись контекста Bitrix24 недействительна");
        }
        String[] payload = new String(payloadBytes, StandardCharsets.UTF_8).split("\\|", -1);
        if (payload.length == 6 && VERSION.equals(payload[0])) {
            try {
                long entityId = positiveLong(payload[5]);
                return new BitrixPlacementContext(
                        nullableLong(payload[1]),
                        payload[2],
                        nullableLong(payload[3]),
                        CrmEntityType.valueOf(payload[4]),
                        entityId);
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("Некорректный контекст Bitrix24", error);
            }
        }
        if (payload.length == 4 && "v2".equals(payload[0])) {
            return new BitrixPlacementContext(null, payload[1], null,
                    CrmEntityType.valueOf(payload[2]), positiveLong(payload[3]));
        }
        if (payload.length == 4) {
            long expiresAt;
            try { expiresAt = Long.parseLong(payload[3]); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("Некорректный контекст Bitrix24", error); }
            if (Instant.now().getEpochSecond() > expiresAt) {
                throw new IllegalArgumentException("Открыта устаревшая форма Bitrix24. Закройте её и снова откройте Видео-оффер");
            }
            return new BitrixPlacementContext(null, payload[0], null,
                    CrmEntityType.valueOf(payload[1]), positiveLong(payload[2]));
        }
        throw new IllegalArgumentException("Некорректный контекст Bitrix24");
    }

    private long positiveLong(String value) {
        long id = Long.parseLong(value);
        if (id <= 0) throw new IllegalArgumentException("Некорректный ID Bitrix24");
        return id;
    }

    private Long nullableLong(String value) {
        if (value == null || value.isBlank() || "-".equals(value)) return null;
        long parsed = Long.parseLong(value);
        return parsed > 0 ? parsed : null;
    }

    private String nullLong(Long value) { return value == null ? "-" : Long.toString(value); }
    private String safe(String value) { return value == null ? "" : value.replace("|", ""); }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(payload);
        } catch (Exception error) {
            throw new IllegalStateException("Не удалось подписать контекст Bitrix24", error);
        }
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
