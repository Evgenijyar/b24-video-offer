package ru.abs7.videooffer.bitrix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.abs7.videooffer.offer.VideoOffer;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class BitrixTimelineService {
    private static final Logger log = LoggerFactory.getLogger(BitrixTimelineService.class);

    private final BitrixRestClient restClient;
    private final BitrixResponsibleEmployeeService responsibleEmployeeService;
    private final String publicBaseUrl;

    public BitrixTimelineService(
            BitrixRestClient restClient,
            BitrixResponsibleEmployeeService responsibleEmployeeService,
            @Value("${app.public-base-url}") String publicBaseUrl) {
        this.restClient = restClient;
        this.responsibleEmployeeService = responsibleEmployeeService;
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
        String comment = renderClientMessage(offer, publicUrl);

        try {
            log.info("Calling crm.timeline.comment.add: offerId={}, entityType={}, entityId={}, publicUrl={}, clientMessageLength={}",
                    offer.getId(), offer.getCrmEntityType().bitrixApiName(), offer.getCrmEntityId(),
                    publicUrl, comment.length());
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
            log.info("Client-ready video offer message added to Bitrix timeline: offerId={}, entityType={}, entityId={}, commentId={}",
                    offer.getId(), offer.getCrmEntityType(), offer.getCrmEntityId(), commentId);
        } catch (Exception error) {
            offer.markBitrixDeliveryError(rootMessage(error));
            log.error("Не удалось добавить сообщение видеооффера {} в Bitrix24: {}",
                    offer.getId(), rootMessage(error), error);
        }
    }

    /**
     * Creates a current CRM todo for the employee currently responsible for
     * the lead/deal/contact. Bitrix recommends crm.activity.todo.add for new
     * integrations; the older crm.activity.add is deprecated.
     */
    public ViewGoalTodo createViewGoalTodo(VideoOffer offer) {
        log.info("Bitrix view-goal todo creation started: offerId={}, memberId={}, entityType={}, entityId={}, goal={}",
                offer.getId(), offer.getBitrixMemberId(), offer.getCrmEntityType(),
                offer.getCrmEntityId(), offer.getViewNotificationGoal());
        if (offer.getBitrixMemberId() == null || offer.getBitrixMemberId().isBlank()) {
            throw new IllegalStateException("Для видеооффера отсутствует member_id Bitrix24");
        }

        long responsibleId = responsibleEmployeeService.resolveResponsibleId(
                offer.getBitrixMemberId(), offer.getCrmEntityType(), offer.getCrmEntityId());

        String goalText = viewGoalText(offer);
        String publicUrl = publicBaseUrl + "/o/" + offer.getPublicToken();
        String title = "Связаться с клиентом — видео просмотрено: " + goalText;
        String description = "Клиент достиг цели просмотра видеооффера: " + goalText + ".\n\n"
                + "Ссылка на видеооффер:\n" + publicUrl;

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("ownerTypeId", offer.getCrmEntityType().bitrixEntityTypeId());
        parameters.put("ownerId", offer.getCrmEntityId());
        parameters.put("deadline", OffsetDateTime.now().toString());
        parameters.put("title", title);
        parameters.put("description", description);
        parameters.put("responsibleId", responsibleId);
        parameters.put("pingOffsets", java.util.List.of(0));

        Map<String, Object> response = restClient.call(
                offer.getBitrixMemberId(),
                "crm.activity.todo.add",
                parameters);

        Long activityId = activityId(response.get("result"));
        if (activityId == null) {
            throw new IllegalStateException("Bitrix24 создал дело, но не вернул его ID");
        }
        log.info("Bitrix view-goal todo created: offerId={}, entityType={}, entityId={}, responsibleId={}, activityId={}, goal={}",
                offer.getId(), offer.getCrmEntityType(), offer.getCrmEntityId(), responsibleId, activityId,
                offer.getViewNotificationGoal());
        return new ViewGoalTodo(activityId, responsibleId);
    }

    /**
     * Sends a native Bitrix24 system notification to the responsible employee.
     * It is intentionally a separate REST call from CRM todo creation: Bitrix24
     * does not guarantee that adding a CRM todo creates a bell notification.
     * A stable TAG makes network retries effectively idempotent on the Bitrix
     * notifications side. Scope `im` is required by Bitrix24.
     */
    public Long sendViewGoalSystemNotification(VideoOffer offer, long responsibleId, long activityId) {
        String goalText = viewGoalText(offer);
        String publicUrl = publicBaseUrl + "/o/" + offer.getPublicToken();
        String message = "[B]Видео просмотрено[/B]\n"
                + "Клиент достиг цели просмотра: " + goalText + ".\n"
                + "Поставлено дело «Связаться с клиентом».\n"
                + "[URL=" + publicUrl + "]Открыть видеооффер[/URL]";
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("USER_ID", responsibleId);
        parameters.put("MESSAGE", message);
        parameters.put("TAG", "VIDEO_OFFER_VIEW_" + offer.getId());

        Map<String, Object> response = restClient.call(
                offer.getBitrixMemberId(),
                "im.notify.system.add",
                parameters);

        Long notificationId = tryParseLong(response.get("result"));
        if (notificationId == null) {
            throw new IllegalStateException("Bitrix24 не вернул ID системного уведомления");
        }
        log.info("Bitrix view-goal system notification sent: offerId={}, entityType={}, entityId={}, responsibleId={}, activityId={}, notificationId={}, goal={}",
                offer.getId(), offer.getCrmEntityType(), offer.getCrmEntityId(), responsibleId, activityId,
                notificationId, offer.getViewNotificationGoal());
        return notificationId;
    }

    public record ViewGoalTodo(long activityId, long responsibleId) {}

    private String renderClientMessage(VideoOffer offer, String publicUrl) {
        String message = offer.getClientMessage();
        if (message == null || message.isBlank()) {
            message = "В продолжение нашего разговора подготовил для вас короткую видеопрезентацию.\n\n"
                    + "Посмотреть можно по ссылке:\n"
                    + BitrixResponsibleEmployeeService.VIDEO_URL_PLACEHOLDER;
        }
        String rendered = message
                .replace(BitrixResponsibleEmployeeService.VIDEO_URL_PLACEHOLDER, publicUrl)
                .replace("[ссылка на видео]", publicUrl)
                .replace("〔ссылка на видео〕", publicUrl)
                .trim();
        if (!rendered.contains(publicUrl)) {
            rendered += "\n\nПосмотреть можно по ссылке:\n" + publicUrl;
        }
        return rendered;
    }

    private String viewGoalText(VideoOffer offer) {
        return switch (offer.getViewNotificationGoal()) {
            case NONE -> "просмотр не отслеживается";
            case ONE_MINUTE -> isShorterThanOneMinute(offer)
                    ? "полный просмотр (ролик короче одной минуты)"
                    : "1 минута";
            case HALF -> "50% видео";
            case COMPLETED -> "полный просмотр";
        };
    }

    private boolean isShorterThanOneMinute(VideoOffer offer) {
        return offer.getViewGoalDurationSeconds() != null
                && offer.getViewGoalDurationSeconds().compareTo(java.math.BigDecimal.valueOf(60)) < 0;
    }

    private Long activityId(Object result) {
        if (result instanceof Number number) {
            return number.longValue();
        }
        if (result instanceof Map<?, ?> map) {
            Object id = map.get("id");
            if (id == null) id = map.get("ID");
            if (id instanceof Number number) return number.longValue();
            return tryParseLong(id);
        }
        return tryParseLong(result);
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
