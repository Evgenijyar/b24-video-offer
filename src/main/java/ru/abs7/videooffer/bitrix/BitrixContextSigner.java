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
    private static final String NON_EXPIRING_VERSION = "v2";

    private final byte[] secret;

    public BitrixContextSigner(BitrixProperties properties) {
        if (properties.clientSecret() == null || properties.clientSecret().isBlank()) {
            throw new IllegalStateException("app.bitrix.client-secret не настроен");
        }
        this.secret = properties.clientSecret().getBytes(StandardCharsets.UTF_8);
    }

    public String create(BitrixPlacementContext context) {
        // Это не OAuth-токен Bitrix24, а наш HMAC-подписанный контекст карточки.
        // Начиная с v2 он не имеет срока действия: подменить сущность или ID без client_secret нельзя.
        String payload = NON_EXPIRING_VERSION + "|"
                + context.memberId() + "|"
                + context.entityType().name() + "|"
                + context.entityId();
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String token = encode(payloadBytes) + "." + encode(sign(payloadBytes));
        log.info("Bitrix context signed: memberId={}, entityType={}, entityId={}, version={}, nonExpiring=true",
                context.memberId(), context.entityType(), context.entityId(), NON_EXPIRING_VERSION);
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

        if (NON_EXPIRING_VERSION.equals(payload[0])) {
            return verifyNonExpiring(payload);
        }

        // Обратная совместимость с уже открытыми формами старой версии:
        // memberId|entityType|entityId|expiresAt.
        return verifyLegacy(payload);
    }

    private BitrixPlacementContext verifyNonExpiring(String[] payload) {
        long entityId = parsePositiveId(payload[3]);
        BitrixPlacementContext context;
        try {
            context = new BitrixPlacementContext(
                    payload[1],
                    CrmEntityType.valueOf(payload[2]),
                    entityId);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Некорректный контекст Bitrix24", error);
        }

        log.info("Bitrix context verified: memberId={}, entityType={}, entityId={}, version={}, nonExpiring=true",
                context.memberId(), context.entityType(), context.entityId(), NON_EXPIRING_VERSION);
        return context;
    }

    private BitrixPlacementContext verifyLegacy(String[] payload) {
        long entityId = parsePositiveId(payload[2]);
        long expiresAt;
        try {
            expiresAt = Long.parseLong(payload[3]);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Некорректный контекст Bitrix24", error);
        }

        if (Instant.now().getEpochSecond() > expiresAt) {
            throw new IllegalArgumentException(
                    "Открыта устаревшая форма Bitrix24. Закройте её и снова нажмите «Сформировать видеооффер»");
        }

        BitrixPlacementContext context;
        try {
            context = new BitrixPlacementContext(
                    payload[0],
                    CrmEntityType.valueOf(payload[1]),
                    entityId);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Некорректный контекст Bitrix24", error);
        }

        log.info("Legacy Bitrix context verified: memberId={}, entityType={}, entityId={}, expiresAt={}",
                context.memberId(), context.entityType(), context.entityId(), expiresAt);
        return context;
    }

    private long parsePositiveId(String value) {
        try {
            long entityId = Long.parseLong(value);
            if (entityId <= 0) {
                throw new IllegalArgumentException("Bitrix24 передал некорректный ID карточки");
            }
            return entityId;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Некорректный контекст Bitrix24", error);
        }
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
