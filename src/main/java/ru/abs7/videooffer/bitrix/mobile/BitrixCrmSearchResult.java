package ru.abs7.videooffer.bitrix.mobile;

import ru.abs7.videooffer.offer.CrmEntityType;

public record BitrixCrmSearchResult(
        CrmEntityType entityType,
        long id,
        String title,
        String subtitle,
        String phone,
        String contextToken) {
}
