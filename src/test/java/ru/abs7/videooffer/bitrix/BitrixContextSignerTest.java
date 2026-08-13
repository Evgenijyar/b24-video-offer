package ru.abs7.videooffer.bitrix;

import org.junit.jupiter.api.Test;
import ru.abs7.videooffer.offer.CrmEntityType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BitrixContextSignerTest {
    private final BitrixContextSigner signer = new BitrixContextSigner(
            new BitrixProperties(
                    "local.test",
                    "test-secret",
                    "https://example.test/bitrix/install",
                    null,
                    null,
                    null,
                    null));

    @Test
    void signsAndVerifiesPlacementContext() {
        BitrixPlacementContext source = new BitrixPlacementContext(
                7L, "member-1", 216L, CrmEntityType.DEAL, 3473L);

        BitrixPlacementContext restored = signer.verify(signer.create(source));

        assertEquals(source, restored);
    }

    @Test
    void rejectsModifiedToken() {
        String token = signer.create(new BitrixPlacementContext(
                7L, "member-1", 216L, CrmEntityType.CONTACT, 13037L));

        assertThrows(IllegalArgumentException.class,
                () -> signer.verify(token + "x"));
    }
}
