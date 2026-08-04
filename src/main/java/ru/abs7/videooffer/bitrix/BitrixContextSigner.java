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
    private static final long TOKEN_LIFETIME_SECONDS = 4 * 60 * 60;

    private final byte[] secret;

    public BitrixContextSigner(BitrixProperties properties) {
        if (properties.clientSecret() == null || properties.clientSecret().isBlank()) {
            throw new IllegalStateException("app.bitrix.client-secret не настроен");
        }
        this.secret = properties.clientSecret().getBytes(StandardCharsets.UTF_8);
    }

    public String create(BitrixPlacementContext context) {
        long expiresAt = Instant.now().getEpochSecond() + TOKEN_LIFETIME_SECONDS;
        String payload = context.memberId() + "|"
                + context.entityType().name() + "|"
                + context.entityId() + "|"
                + expiresAt;
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String token = encode(payloadBytes) + "." + encode(sign(payloadBytes));
        log.info("Bitrix context signed: memberId={}, entityType={}, entityId={}, expiresAt={}",
                context.memberId(), context.entityType(), context.entityId(), expiresAt);
        return token;
    }

    public BitrixPlacementContext verify(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Контекст Bitrix24 не передан");
        }

        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Некорректный контекст Bitrix24");
        }

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
        if (payload.length != 4) {
            throw new IllegalArgumentException("Некорректный контекст Bitrix24");
        }

        long entityId;
        long expiresAt;
        try {
            entityId = Long.parseLong(payload[2]);
            expiresAt = Long.parseLong(payload[3]);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Некорректный контекст Bitrix24", error);
        }

        if (entityId <= 0 || Instant.now().getEpochSecond() > expiresAt) {
            throw new IllegalArgumentException("Контекст Bitrix24 истёк или содержит неверный ID");
        }

        BitrixPlacementContext context = new BitrixPlacementContext(
                payload[0],
                CrmEntityType.valueOf(payload[1]),
                entityId);
        log.info("Bitrix context verified: memberId={}, entityType={}, entityId={}, expiresAt={}",
                context.memberId(), context.entityType(), context.entityId(), expiresAt);
        return context;
    }

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
