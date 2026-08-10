package ru.abs7.videooffer.offer;

import java.util.Locale;

public enum CrmEntityType {
    DEAL("deal", "Сделка", 2),
    LEAD("lead", "Лид", 1),
    CONTACT("contact", "Контакт", 3);

    private final String bitrixApiName;
    private final String russianLabel;
    private final int bitrixEntityTypeId;

    CrmEntityType(String bitrixApiName, String russianLabel, int bitrixEntityTypeId) {
        this.bitrixApiName = bitrixApiName;
        this.russianLabel = russianLabel;
        this.bitrixEntityTypeId = bitrixEntityTypeId;
    }

    public String bitrixApiName() {
        return bitrixApiName;
    }

    public String russianLabel() {
        return russianLabel;
    }

    public int bitrixEntityTypeId() {
        return bitrixEntityTypeId;
    }

    public static CrmEntityType fromBitrixPlacement(String placement) {
        if (placement == null) {
            throw new IllegalArgumentException("Bitrix24 не передал PLACEMENT");
        }

        return switch (placement.trim().toUpperCase(Locale.ROOT)) {
            case "CRM_DEAL_DETAIL_ACTIVITY",
                    "CRM_DEAL_DETAIL_TOOLBAR",
                    "CRM_DEAL_DETAIL_TAB" -> DEAL;
            case "CRM_LEAD_DETAIL_ACTIVITY",
                    "CRM_LEAD_DETAIL_TOOLBAR",
                    "CRM_LEAD_DETAIL_TAB" -> LEAD;
            case "CRM_CONTACT_DETAIL_ACTIVITY",
                    "CRM_CONTACT_DETAIL_TOOLBAR",
                    "CRM_CONTACT_DETAIL_TAB" -> CONTACT;
            default -> throw new IllegalArgumentException(
                    "Неподдерживаемое место встраивания Bitrix24: " + placement);
        };
    }
}
