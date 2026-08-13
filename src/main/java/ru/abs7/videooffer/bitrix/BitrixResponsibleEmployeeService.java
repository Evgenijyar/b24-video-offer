package ru.abs7.videooffer.bitrix;

import org.springframework.stereotype.Service;
import ru.abs7.videooffer.offer.CrmEntityType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves only the current CRM responsible user id.
 *
 * Employee phones/e-mail are intentionally NOT read here anymore. In the
 * multi-tenant version all client-facing text/signature defaults are managed
 * explicitly by the client administrator in Video Offer settings.
 */
@Service
public class BitrixResponsibleEmployeeService {
    private final BitrixRestClient restClient;

    public BitrixResponsibleEmployeeService(BitrixRestClient restClient) {
        this.restClient = restClient;
    }

    public long resolveResponsibleId(String memberId, CrmEntityType entityType, long entityId) {
        Map<String, Object> itemResponse = restClient.call(
                memberId,
                "crm.item.get",
                Map.of(
                        "entityTypeId", entityType.bitrixEntityTypeId(),
                        "id", entityId,
                        "useOriginalUfNames", "N"));

        Map<String, Object> result = map(itemResponse.get("result"));
        Map<String, Object> item = map(result.get("item"));
        long responsibleId = positiveLong(first(item, "assignedById", "ASSIGNED_BY_ID"));
        if (responsibleId <= 0) {
            throw new IllegalStateException("Bitrix24 не вернул ответственного сотрудника для "
                    + entityType + " №" + entityId);
        }
        return responsibleId;
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, nested) -> result.put(String.valueOf(key), nested));
        return result;
    }

    private Object first(Map<String, Object> map, String... keys) {
        for (String key : keys) if (map.containsKey(key)) return map.get(key);
        return null;
    }

    private long positiveLong(Object value) {
        if (value instanceof Number number) return Math.max(0, number.longValue());
        if (value == null) return 0;
        try { return Math.max(0, Long.parseLong(String.valueOf(value))); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
