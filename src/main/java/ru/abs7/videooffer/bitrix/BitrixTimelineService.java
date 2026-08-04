package ru.abs7.videooffer.bitrix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.abs7.videooffer.offer.VideoOffer;

import java.util.Map;

@Service
public class BitrixTimelineService {
    private static final Logger log = LoggerFactory.getLogger(BitrixTimelineService.class);

    private final BitrixRestClient restClient;
    private final String publicBaseUrl;

    public BitrixTimelineService(
            BitrixRestClient restClient,
            @Value("${app.public-base-url}") String publicBaseUrl) {
        this.restClient = restClient;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    public void publishReadyLink(VideoOffer offer) {
        log.info("Bitrix timeline publication started: offerId={}, memberId={}, entityType={}, entityId={}",
                offer.getId(), offer.getBitrixMemberId(), offer.getCrmEntityType(), offer.getCrmEntityId());
        if (offer.getBitrixMemberId() == null || offer.getBitrixMemberId().isBlank()) {
            offer.markBitrixDeliveryNotRequired();
            log.info("Bitrix timeline publication not required: offerId={}, reason=no-member-id", offer.getId());
            return;
        }

        String publicUrl = publicBaseUrl + "/o/" + offer.getPublicToken();
        String comment = "Видео-оффер готов.\n\nСсылка: " + publicUrl;

        try {
            log.info("Calling crm.timeline.comment.add: offerId={}, entityType={}, entityId={}, publicUrl={}",
                    offer.getId(), offer.getCrmEntityType().bitrixApiName(), offer.getCrmEntityId(), publicUrl);
            Map<String, Object> response = restClient.call(
                    offer.getBitrixMemberId(),
                    "crm.timeline.comment.add",
                    Map.of("fields", Map.of(
                            "ENTITY_ID", offer.getCrmEntityId(),
                            "ENTITY_TYPE", offer.getCrmEntityType().bitrixApiName(),
                            "COMMENT", comment)));

            Object result = response.get("result");
            Long commentId = result instanceof Number number
                    ? number.longValue()
                    : tryParseLong(result);
            offer.markBitrixDelivered(commentId);
            log.info("Ссылка видеооффера {} добавлена в {} №{}",
                    offer.getId(), offer.getCrmEntityType(), offer.getCrmEntityId());
        } catch (Exception error) {
            offer.markBitrixDeliveryError(rootMessage(error));
            log.error("Не удалось добавить ссылку видеооффера {} в Bitrix24: {}",
                    offer.getId(), rootMessage(error), error);
        }
    }

    private Long tryParseLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? error.getClass().getSimpleName()
                : current.getMessage();
    }
}
