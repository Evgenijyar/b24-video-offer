package ru.abs7.videooffer.bitrix.mobile;

import org.junit.jupiter.api.Test;
import ru.abs7.videooffer.bitrix.BitrixProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void signsAndVerifiesActorContext() {
        String token = signer.create(7L, "member-123", 216L, true);
        var actor = signer.verify(token);
        assertEquals(7L, actor.tenantId());
        assertEquals("member-123", actor.memberId());
        assertEquals(216L, actor.bitrixUserId());
        assertTrue(actor.admin());
    }

    @Test
    void rejectsModifiedToken() {
        String token = signer.create(7L, "member-123", 216L, false);
        assertThrows(IllegalArgumentException.class,
                () -> signer.verify(token.substring(0, token.length() - 1) + "A"));
    }
}
