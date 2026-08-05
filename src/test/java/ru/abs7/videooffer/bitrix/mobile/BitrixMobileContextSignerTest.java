package ru.abs7.videooffer.bitrix.mobile;

import org.junit.jupiter.api.Test;
import ru.abs7.videooffer.bitrix.BitrixProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BitrixMobileContextSignerTest {

    private final BitrixMobileContextSigner signer = new BitrixMobileContextSigner(
            new BitrixProperties(
                    "client",
                    "very-secret-value",
                    "https://example.test/install",
                    10,
                    30,
                    false,
                    new BitrixProperties.ProxySettings(false, null, null, null, null)));

    @Test
    void signsAndVerifiesMemberId() {
        String token = signer.create("member-123");
        assertEquals("member-123", signer.verify(token));
    }

    @Test
    void rejectsModifiedToken() {
        String token = signer.create("member-123");
        assertThrows(IllegalArgumentException.class,
                () -> signer.verify(token.substring(0, token.length() - 1) + "A"));
    }
}
