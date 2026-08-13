package ru.abs7.videooffer.bitrix;

import ru.abs7.videooffer.offer.CrmEntityType;

public record BitrixPlacementContext(
        Long tenantId,
        String memberId,
        Long bitrixUserId,
        CrmEntityType entityType,
        long entityId) {

    public BitrixPlacementContext(String memberId, CrmEntityType entityType, long entityId) {
        this(null, memberId, null, entityType, entityId);
    }
}
