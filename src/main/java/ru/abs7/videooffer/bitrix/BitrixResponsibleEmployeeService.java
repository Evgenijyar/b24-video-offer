package ru.abs7.videooffer.bitrix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.abs7.videooffer.offer.CrmEntityType;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BitrixResponsibleEmployeeService {
    private static final Logger log = LoggerFactory.getLogger(BitrixResponsibleEmployeeService.class);
    public static final String VIDEO_URL_PLACEHOLDER = "{{VIDEO_URL}}";

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

    public ResponsibleEmployee resolve(String memberId, CrmEntityType entityType, long entityId) {
        long responsibleId = resolveResponsibleId(memberId, entityType, entityId);

        Map<String, Object> userResponse = restClient.call(
                memberId,
                "user.get",
                Map.of("filter", Map.of("ID", responsibleId)));
        Map<String, Object> user = firstUser(userResponse.get("result"));
        if (user.isEmpty()) {
            throw new IllegalStateException("Bitrix24 не вернул данные ответственного сотрудника ID=" + responsibleId);
        }

        ResponsibleEmployee employee = new ResponsibleEmployee(
                responsibleId,
                joinName(
                        string(first(user, "NAME", "name")),
                        string(first(user, "SECOND_NAME", "secondName")),
                        string(first(user, "LAST_NAME", "lastName"))),
                normalize(string(first(user, "PERSONAL_MOBILE", "personalMobile"))),
                normalize(string(first(user, "WORK_PHONE", "workPhone"))),
                normalize(string(first(user, "PERSONAL_PHONE", "personalPhone"))),
                normalize(string(first(user, "EMAIL", "email"))));

        log.info("Bitrix responsible employee resolved: memberId={}, entityType={}, entityId={}, responsibleId={}, name={}, personalMobilePresent={}, workPhonePresent={}, personalPhonePresent={}, emailPresent={}",
                memberId, entityType, entityId, responsibleId, employee.fullName(),
                employee.personalMobile() != null, employee.workPhone() != null,
                employee.personalPhone() != null, employee.email() != null);
        return employee;
    }

    public ClientMessageTemplate buildClientMessageTemplate(
            String memberId,
            CrmEntityType entityType,
            long entityId) {
        ResponsibleEmployee employee = resolve(memberId, entityType, entityId);
        return new ClientMessageTemplate(employee, buildTemplate(employee));
    }

    public String buildTemplate(ResponsibleEmployee employee) {
        StringBuilder text = new StringBuilder();
        text.append("В продолжение нашего разговора подготовил для вас короткую видеопрезентацию.\n\n")
                .append("Посмотреть можно по ссылке:\n")
                .append(VIDEO_URL_PLACEHOLDER)
                .append("\n\n")
                .append("Связаться со мной можно:");

        if (employee.fullName() != null) {
            text.append("\n").append(employee.fullName());
        }
        if (employee.personalMobile() != null) {
            text.append("\nМобильный телефон: ").append(employee.personalMobile());
            text.append("\nWhatsApp: ").append(employee.personalMobile());
            text.append("\nMAX: ").append(employee.personalMobile());
            text.append("\nTelegram: ").append(employee.personalMobile());
        }
        if (employee.workPhone() != null && !samePhone(employee.workPhone(), employee.personalMobile())) {
            text.append("\nРабочий телефон: ").append(employee.workPhone());
        }
        if (employee.personalPhone() != null
                && !samePhone(employee.personalPhone(), employee.personalMobile())
                && !samePhone(employee.personalPhone(), employee.workPhone())) {
            text.append("\nТелефон: ").append(employee.personalPhone());
        }
        if (employee.email() != null) {
            text.append("\nE-mail: ").append(employee.email());
        }
        return text.toString();
    }

    public String fallbackTemplate() {
        return "В продолжение нашего разговора подготовил для вас короткую видеопрезентацию.\n\n"
                + "Посмотреть можно по ссылке:\n"
                + VIDEO_URL_PLACEHOLDER
                + "\n\nСвязаться со мной можно:";
    }

    private boolean samePhone(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        String a = first.replaceAll("\\D", "");
        String b = second.replaceAll("\\D", "");
        return !a.isBlank() && a.equals(b);
    }

    private Map<String, Object> firstUser(Object value) {
        if (value instanceof Collection<?> values) {
            for (Object item : values) {
                Map<String, Object> user = map(item);
                if (!user.isEmpty()) {
                    return user;
                }
            }
        }
        return Map.of();
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, nested) -> result.put(String.valueOf(key), nested));
        return result;
    }

    private Object first(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private long positiveLong(Object value) {
        if (value instanceof Number number) {
            return Math.max(0, number.longValue());
        }
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(String.valueOf(value)));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String joinName(String firstName, String secondName, String lastName) {
        return List.of(firstName, secondName, lastName).stream()
                .map(this::normalize)
                .filter(value -> value != null)
                .reduce((left, right) -> left + " " + right)
                .orElse(null);
    }

    public record ResponsibleEmployee(
            long id,
            String fullName,
            String personalMobile,
            String workPhone,
            String personalPhone,
            String email) {
    }

    public record ClientMessageTemplate(
            ResponsibleEmployee responsible,
            String message) {
    }
}
