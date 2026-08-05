package ru.abs7.videooffer.bitrix.mobile;

import ru.abs7.videooffer.offer.CrmEntityType;

import java.util.List;

public record BitrixCrmSearchResponse(
        CrmEntityType entityType,
        String query,
        List<BitrixCrmSearchResult> results) {
}
