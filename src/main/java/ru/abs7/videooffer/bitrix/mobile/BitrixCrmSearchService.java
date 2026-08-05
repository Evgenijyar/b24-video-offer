package ru.abs7.videooffer.bitrix.mobile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.abs7.videooffer.bitrix.BitrixContextSigner;
import ru.abs7.videooffer.bitrix.BitrixPlacementContext;
import ru.abs7.videooffer.bitrix.BitrixRestClient;
import ru.abs7.videooffer.bitrix.BitrixRestException;
import ru.abs7.videooffer.offer.CrmEntityType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class BitrixCrmSearchService {
    private static final Logger log = LoggerFactory.getLogger(BitrixCrmSearchService.class);
    private static final int RESULT_LIMIT = 20;
    private static final int RELATED_CLIENT_LIMIT = 15;

    private final BitrixRestClient restClient;
    private final BitrixContextSigner contextSigner;

    public BitrixCrmSearchService(
            BitrixRestClient restClient,
            BitrixContextSigner contextSigner) {
        this.restClient = restClient;
        this.contextSigner = contextSigner;
    }

    public List<BitrixCrmSearchResult> search(
            String memberId,
            CrmEntityType entityType,
            String rawQuery) {
        String query = normalizeQuery(rawQuery);
        if (query.length() < 2) {
            throw new IllegalArgumentException("Введите минимум 2 символа для поиска");
        }

        log.info("Mobile CRM search started: memberId={}, entityType={}, queryLength={}, phoneLike={}",
                memberId, entityType, query.length(), isPhoneLike(query));

        LinkedHashMap<Long, SearchItem> merged = new LinkedHashMap<>();
        addAll(merged, searchByText(memberId, entityType, query));

        if (isPhoneLike(query)) {
            addAll(merged, searchByPhone(memberId, entityType, query));
        }

        if (entityType == CrmEntityType.DEAL && merged.size() < RESULT_LIMIT && query.length() >= 3) {
            addAll(merged, searchDealsByClientName(memberId, query));
        }

        List<BitrixCrmSearchResult> results = merged.values().stream()
                .limit(RESULT_LIMIT)
                .map(item -> toResult(memberId, entityType, item))
                .toList();

        log.info("Mobile CRM search completed: memberId={}, entityType={}, queryLength={}, resultCount={}",
                memberId, entityType, query.length(), results.size());
        return results;
    }

    private List<SearchItem> searchByText(
            String memberId,
            CrmEntityType entityType,
            String query) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("entityTypeId", entityTypeId(entityType));
        parameters.put("select", List.of("*"));
        parameters.put("filter", textFilter(entityType, query, true));
        parameters.put("order", Map.of("updatedTime", "DESC"));
        parameters.put("start", 0);

        try {
            return parseUniversalItems(restClient.call(memberId, "crm.item.list", parameters), null);
        } catch (BitrixRestException error) {
            // На старых порталах searchContent может отсутствовать в доступных фильтрах.
            log.warn("CRM text search with searchContent failed, retrying with basic fields: memberId={}, entityType={}, errorCode={}, error={}",
                    memberId, entityType, error.getErrorCode(), error.getMessage());
            parameters.put("filter", textFilter(entityType, query, false));
            return parseUniversalItems(restClient.call(memberId, "crm.item.list", parameters), null);
        }
    }

    private Map<String, Object> textFilter(
            CrmEntityType entityType,
            String query,
            boolean includeSearchContent) {
        LinkedHashMap<String, Object> or = new LinkedHashMap<>();
        or.put("logic", "OR");
        int index = 0;

        if (query.chars().allMatch(Character::isDigit)) {
            try {
                or.put(Integer.toString(index++), Map.of("id", Long.parseLong(query)));
            } catch (NumberFormatException ignored) {
                // Very long numeric input: continue with text/phone lookup.
            }
        }

        switch (entityType) {
            case LEAD -> {
                or.put(Integer.toString(index++), Map.of("%title", query));
                or.put(Integer.toString(index++), Map.of("%name", query));
                or.put(Integer.toString(index++), Map.of("%lastName", query));
                or.put(Integer.toString(index++), Map.of("%secondName", query));
            }
            case CONTACT -> {
                or.put(Integer.toString(index++), Map.of("%name", query));
                or.put(Integer.toString(index++), Map.of("%lastName", query));
                or.put(Integer.toString(index++), Map.of("%secondName", query));
            }
            case DEAL -> or.put(Integer.toString(index++), Map.of("%title", query));
        }

        if (includeSearchContent) {
            or.put(Integer.toString(index), Map.of("%searchContent", query));
        }
        return Map.of("0", or);
    }

    private List<SearchItem> searchByPhone(
            String memberId,
            CrmEntityType entityType,
            String query) {
        Set<String> values = phoneVariants(query);
        if (values.isEmpty()) {
            return List.of();
        }

        Map<String, Object> duplicateResponse = restClient.call(
                memberId,
                "crm.duplicate.findbycomm",
                Map.of("type", "PHONE", "values", List.copyOf(values)));
        Map<String, Object> duplicateIds = resultMap(duplicateResponse);

        if (entityType == CrmEntityType.LEAD) {
            return fetchItemsByIds(memberId, entityType, ids(duplicateIds.get("LEAD")), query);
        }
        if (entityType == CrmEntityType.CONTACT) {
            return fetchItemsByIds(memberId, entityType, ids(duplicateIds.get("CONTACT")), query);
        }

        List<Long> contactIds = ids(duplicateIds.get("CONTACT"));
        List<Long> companyIds = ids(duplicateIds.get("COMPANY"));
        return fetchDealsByClients(memberId, contactIds, companyIds, query);
    }

    private List<SearchItem> searchDealsByClientName(String memberId, String query) {
        List<Long> contactIds = searchRelatedClientIds(memberId, 3, query);
        List<Long> companyIds = searchRelatedClientIds(memberId, 4, query);
        if (contactIds.isEmpty() && companyIds.isEmpty()) {
            return List.of();
        }
        return fetchDealsByClients(memberId, contactIds, companyIds, null);
    }

    private List<Long> searchRelatedClientIds(
            String memberId,
            int entityTypeId,
            String query) {
        LinkedHashMap<String, Object> or = new LinkedHashMap<>();
        or.put("logic", "OR");
        if (entityTypeId == 3) {
            or.put("0", Map.of("%name", query));
            or.put("1", Map.of("%lastName", query));
            or.put("2", Map.of("%secondName", query));
            or.put("3", Map.of("%searchContent", query));
        } else {
            or.put("0", Map.of("%title", query));
            or.put("1", Map.of("%searchContent", query));
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("entityTypeId", entityTypeId);
        parameters.put("select", List.of("id"));
        parameters.put("filter", Map.of("0", or));
        parameters.put("order", Map.of("updatedTime", "DESC"));
        parameters.put("start", 0);

        try {
            return parseUniversalItems(restClient.call(memberId, "crm.item.list", parameters), null)
                    .stream()
                    .map(SearchItem::id)
                    .limit(RELATED_CLIENT_LIMIT)
                    .toList();
        } catch (BitrixRestException error) {
            log.warn("Related CRM client search failed and will be skipped: memberId={}, entityTypeId={}, errorCode={}, error={}",
                    memberId, entityTypeId, error.getErrorCode(), error.getMessage());
            return List.of();
        }
    }

    private List<SearchItem> fetchItemsByIds(
            String memberId,
            CrmEntityType entityType,
            List<Long> ids,
            String matchedPhone) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("entityTypeId", entityTypeId(entityType));
        parameters.put("select", List.of("*"));
        parameters.put("filter", Map.of("@id", ids.stream().limit(50).toList()));
        parameters.put("order", Map.of("updatedTime", "DESC"));
        parameters.put("start", 0);
        return parseUniversalItems(restClient.call(memberId, "crm.item.list", parameters), matchedPhone);
    }

    private List<SearchItem> fetchDealsByClients(
            String memberId,
            List<Long> contactIds,
            List<Long> companyIds,
            String matchedPhone) {
        if (contactIds.isEmpty() && companyIds.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<String, Object> or = new LinkedHashMap<>();
        or.put("logic", "OR");
        int index = 0;
        if (!contactIds.isEmpty()) {
            List<Long> limited = contactIds.stream().limit(50).toList();
            or.put(Integer.toString(index++), Map.of("@contactId", limited));
            or.put(Integer.toString(index++), Map.of("@contactIds", limited));
        }
        if (!companyIds.isEmpty()) {
            or.put(Integer.toString(index), Map.of("@companyId", companyIds.stream().limit(50).toList()));
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("entityTypeId", 2);
        parameters.put("select", List.of("*"));
        parameters.put("filter", Map.of("0", or));
        parameters.put("order", Map.of("updatedTime", "DESC"));
        parameters.put("start", 0);

        try {
            return parseUniversalItems(restClient.call(memberId, "crm.item.list", parameters), matchedPhone);
        } catch (BitrixRestException error) {
            log.warn("Universal linked-deal search failed, using legacy fallback: memberId={}, errorCode={}, error={}",
                    memberId, error.getErrorCode(), error.getMessage());
            return fetchDealsLegacy(memberId, contactIds, companyIds, matchedPhone);
        }
    }

    private List<SearchItem> fetchDealsLegacy(
            String memberId,
            List<Long> contactIds,
            List<Long> companyIds,
            String matchedPhone) {
        LinkedHashMap<Long, SearchItem> merged = new LinkedHashMap<>();
        if (!contactIds.isEmpty()) {
            addAll(merged, parseLegacyItems(restClient.call(
                    memberId,
                    "crm.deal.list",
                    Map.of(
                            "filter", Map.of("CONTACT_ID", contactIds.stream().limit(50).toList()),
                            "select", List.of("ID", "TITLE", "CONTACT_ID", "COMPANY_ID"),
                            "order", Map.of("DATE_MODIFY", "DESC"))), matchedPhone));
        }
        if (!companyIds.isEmpty()) {
            addAll(merged, parseLegacyItems(restClient.call(
                    memberId,
                    "crm.deal.list",
                    Map.of(
                            "filter", Map.of("COMPANY_ID", companyIds.stream().limit(50).toList()),
                            "select", List.of("ID", "TITLE", "CONTACT_ID", "COMPANY_ID"),
                            "order", Map.of("DATE_MODIFY", "DESC"))), matchedPhone));
        }
        return new ArrayList<>(merged.values());
    }

    private List<SearchItem> parseUniversalItems(
            Map<String, Object> response,
            String matchedPhone) {
        Map<String, Object> result = resultMap(response);
        Object itemsValue = result.get("items");
        if (!(itemsValue instanceof Collection<?> items)) {
            return List.of();
        }

        List<SearchItem> parsed = new ArrayList<>();
        for (Object value : items) {
            if (value instanceof Map<?, ?> raw) {
                Map<String, Object> item = stringMap(raw);
                long id = positiveLong(item.get("id"));
                if (id > 0) {
                    parsed.add(new SearchItem(id, item, matchedPhone));
                }
            }
        }
        return parsed;
    }

    private List<SearchItem> parseLegacyItems(
            Map<String, Object> response,
            String matchedPhone) {
        Object resultValue = response.get("result");
        if (!(resultValue instanceof Collection<?> items)) {
            return List.of();
        }
        List<SearchItem> parsed = new ArrayList<>();
        for (Object value : items) {
            if (value instanceof Map<?, ?> raw) {
                Map<String, Object> item = stringMap(raw);
                long id = positiveLong(first(item, "ID", "id"));
                if (id > 0) {
                    parsed.add(new SearchItem(id, item, matchedPhone));
                }
            }
        }
        return parsed;
    }

    private BitrixCrmSearchResult toResult(
            String memberId,
            CrmEntityType entityType,
            SearchItem searchItem) {
        Map<String, Object> item = searchItem.fields();
        String phone = firstPhone(item);
        if ((phone == null || phone.isBlank()) && searchItem.matchedPhone() != null) {
            phone = searchItem.matchedPhone();
        }
        String title = itemTitle(entityType, searchItem.id(), item);
        String subtitle = itemSubtitle(entityType, item, phone);
        String contextToken = contextSigner.create(
                new BitrixPlacementContext(memberId, entityType, searchItem.id()));
        return new BitrixCrmSearchResult(
                entityType,
                searchItem.id(),
                title,
                subtitle,
                phone,
                contextToken);
    }

    private String itemTitle(
            CrmEntityType entityType,
            long id,
            Map<String, Object> item) {
        if (entityType == CrmEntityType.CONTACT) {
            String fullName = joinNonBlank(
                    string(first(item, "lastName", "LAST_NAME")),
                    string(first(item, "name", "NAME")),
                    string(first(item, "secondName", "SECOND_NAME")));
            if (!fullName.isBlank()) {
                return fullName;
            }
        }
        String title = string(first(item, "title", "TITLE"));
        return title.isBlank() ? entityType.russianLabel() + " №" + id : title;
    }

    private String itemSubtitle(
            CrmEntityType entityType,
            Map<String, Object> item,
            String phone) {
        List<String> parts = new ArrayList<>();
        if (phone != null && !phone.isBlank()) {
            parts.add(phone);
        }
        if (entityType == CrmEntityType.DEAL) {
            long contactId = positiveLong(first(item, "contactId", "CONTACT_ID"));
            long companyId = positiveLong(first(item, "companyId", "COMPANY_ID"));
            if (contactId > 0) {
                parts.add("контакт №" + contactId);
            }
            if (companyId > 0) {
                parts.add("компания №" + companyId);
            }
        } else if (entityType == CrmEntityType.LEAD) {
            String companyTitle = string(first(item, "companyTitle", "COMPANY_TITLE"));
            if (!companyTitle.isBlank()) {
                parts.add(companyTitle);
            }
        }
        return String.join(" · ", parts);
    }

    private String firstPhone(Map<String, Object> item) {
        for (String key : List.of("phone", "phoneMobile", "phoneWork", "phoneMailing",
                "PHONE", "PHONE_MOBILE", "PHONE_WORK")) {
            String value = extractPhoneValue(item.get(key));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return extractPhoneValue(item.get("fm"));
    }

    private String extractPhoneValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text.isBlank() ? null : text.trim();
        }
        if (value instanceof Collection<?> values) {
            for (Object nested : values) {
                String found = extractPhoneValue(nested);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = stringMap(raw);
            String typeId = string(first(map, "typeId", "TYPE_ID"));
            if (!typeId.isBlank() && !"PHONE".equalsIgnoreCase(typeId)) {
                return null;
            }
            Object direct = first(map, "value", "VALUE");
            if (direct != null) {
                return string(direct);
            }
            for (Object nested : map.values()) {
                String found = extractPhoneValue(nested);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private Map<String, Object> resultMap(Map<String, Object> response) {
        Object value = response.get("result");
        if (value instanceof Map<?, ?> raw) {
            return stringMap(raw);
        }
        return Map.of();
    }

    private List<Long> ids(Object value) {
        if (!(value instanceof Collection<?> values)) {
            return List.of();
        }
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (Object item : values) {
            long id = positiveLong(item);
            if (id > 0) {
                result.add(id);
            }
        }
        return List.copyOf(result);
    }

    private Set<String> phoneVariants(String query) {
        String digits = query.replaceAll("\\D", "");
        if (digits.length() < 7) {
            return Set.of();
        }
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        variants.add(query);
        variants.add(digits);
        variants.add("+" + digits);
        return variants;
    }

    private boolean isPhoneLike(String query) {
        String digits = query.replaceAll("\\D", "");
        return digits.length() >= 7
                && query.chars().filter(Character::isLetter).count() == 0;
    }

    private String normalizeQuery(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() > 100) {
            throw new IllegalArgumentException("Поисковый запрос слишком длинный");
        }
        return normalized;
    }

    private int entityTypeId(CrmEntityType type) {
        return switch (type) {
            case LEAD -> 1;
            case DEAL -> 2;
            case CONTACT -> 3;
        };
    }

    private void addAll(
            LinkedHashMap<Long, SearchItem> destination,
            Collection<SearchItem> source) {
        for (SearchItem item : source) {
            destination.putIfAbsent(item.id(), item);
            if (destination.size() >= RESULT_LIMIT) {
                return;
            }
        }
    }

    private Map<String, Object> stringMap(Map<?, ?> source) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private Object first(Map<String, Object> item, String... keys) {
        for (String key : keys) {
            if (item.containsKey(key)) {
                return item.get(key);
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
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String joinNonBlank(String... values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value.trim());
            }
        }
        return String.join(" ", result);
    }

    private record SearchItem(long id, Map<String, Object> fields, String matchedPhone) {
    }
}
