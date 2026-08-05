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
    private static final String VERSION = "m1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    public BitrixMobileContextSigner(BitrixProperties properties) {
        if (properties.clientSecret() == null || properties.clientSecret().isBlank()) {
            throw new IllegalStateException("app.bitrix.client-secret не настроен");
        }
        this.secret = properties.clientSecret().getBytes(StandardCharsets.UTF_8);
    }

    public String create(String memberId) {
        if (memberId == null || memberId.isBlank()) {
            throw new IllegalArgumentException("member_id Bitrix24 не передан");
        }
        String payload = VERSION + "|" + memberId.trim();
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return encode(payloadBytes) + "." + encode(sign(payloadBytes));
    }

    public String verify(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Мобильный контекст Bitrix24 не передан");
        }

        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Некорректный мобильный контекст Bitrix24");
        }

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
        if (payload.length != 2 || !VERSION.equals(payload[0]) || payload[1].isBlank()) {
            throw new IllegalArgumentException("Некорректный мобильный контекст Bitrix24");
        }
        return payload[1];
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

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
