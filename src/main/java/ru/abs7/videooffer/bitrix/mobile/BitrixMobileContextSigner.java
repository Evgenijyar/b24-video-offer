package ru.abs7.videooffer.bitrix.mobile;

import org.springframework.stereotype.Service;
import ru.abs7.videooffer.bitrix.BitrixProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

@Service
public class BitrixMobileContextSigner {
    private static final String VERSION = "m2";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final byte[] secret;

    public BitrixMobileContextSigner(BitrixProperties properties) {
        if (properties.clientSecret() == null || properties.clientSecret().isBlank()) {
            throw new IllegalStateException("app.bitrix.client-secret не настроен");
        }
        this.secret = properties.clientSecret().getBytes(StandardCharsets.UTF_8);
    }

    public String create(Long tenantId, String memberId, Long bitrixUserId, boolean admin) {
        if (tenantId == null || tenantId <= 0 || memberId == null || memberId.isBlank()
                || bitrixUserId == null || bitrixUserId <= 0) {
            throw new IllegalArgumentException("Не удалось сформировать мобильный контекст Bitrix24");
        }
        String payload = VERSION + "|" + tenantId + "|" + memberId.trim() + "|" + bitrixUserId + "|" + (admin ? "1" : "0");
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return encode(payloadBytes) + "." + encode(sign(payloadBytes));
    }

    public MobileActorContext verify(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Мобильный контекст Bitrix24 не передан");
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) throw new IllegalArgumentException("Некорректный мобильный контекст Bitrix24");
        byte[] payloadBytes;
        byte[] suppliedSignature;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(parts[0]);
            suppliedSignature = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Некорректный мобильный контекст Bitrix24", error);
        }
        if (!MessageDigest.isEqual(sign(payloadBytes), suppliedSignature)) {
            throw new IllegalArgumentException("Подпись мобильного контекста Bitrix24 недействительна");
        }
        String[] payload = new String(payloadBytes, StandardCharsets.UTF_8).split("\\|", -1);
        if (payload.length != 5 || !VERSION.equals(payload[0])) {
            throw new IllegalArgumentException("Открыта устаревшая мобильная форма. Закройте и заново откройте Видео-оффер");
        }
        try {
            return new MobileActorContext(Long.parseLong(payload[1]), payload[2], Long.parseLong(payload[3]), "1".equals(payload[4]));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Некорректный мобильный контекст Bitrix24", error);
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(payload);
        } catch (Exception error) {
            throw new IllegalStateException("Не удалось подписать мобильный контекст Bitrix24", error);
        }
    }

    private String encode(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }

    public record MobileActorContext(Long tenantId, String memberId, Long bitrixUserId, boolean admin) {}
}
