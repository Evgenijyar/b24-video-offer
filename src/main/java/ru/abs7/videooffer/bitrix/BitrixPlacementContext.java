package ru.abs7.videooffer.bitrix;

import ru.abs7.videooffer.offer.CrmEntityType;

public record BitrixPlacementContext(
        String memberId,
        CrmEntityType entityType,
        long entityId) {
}
