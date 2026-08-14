package ru.abs7.videooffer.bitrix.mobile.upload;

import org.junit.jupiter.api.Test;
import ru.abs7.videooffer.offer.CrmEntityType;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MobileVideoUploadClaimTest {
    @Test
    void keepsFutureOfferIdentityOutOfForeignKeyUntilOfferExists() {
        MobileVideoUpload upload = MobileVideoUpload.create(
                "member", CrmEntityType.LEAD, 42L, 7L, 11L,
                "video/mp4", MobileVideoSourceKind.FILE, 1024L, ".", 24);
        upload.acceptChunk(0, 1024L);
        upload.markUploaded(1024L, 1);
        upload.markProcessing();
        upload.markReady("ready.mp4", 1024L);

        UUID futureOfferId = UUID.randomUUID();
        upload.markConsuming(futureOfferId, 1024L, 7L, 11L);

        assertEquals(futureOfferId, upload.getOfferClaimId());
        assertNull(upload.getVideoOfferId(), "FK-backed video_offer_id must remain null before VideoOffer is persisted");

        upload.markConsumed(futureOfferId);

        assertNull(upload.getOfferClaimId());
        assertEquals(futureOfferId, upload.getVideoOfferId());
    }
}
