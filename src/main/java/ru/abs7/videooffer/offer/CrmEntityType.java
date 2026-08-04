package ru.abs7.videooffer.offer;

import java.util.Locale;

public enum CrmEntityType {
    DEAL("deal", "Сделка"),
    LEAD("lead", "Лид"),
    CONTACT("contact", "Контакт");

    private final String bitrixApiName;
    private final String russianLabel;

    CrmEntityType(String bitrixApiName, String russianLabel) {
        this.bitrixApiName = bitrixApiName;
        this.russianLabel = russianLabel;
    }

    public String bitrixApiName() {
        return bitrixApiName;
    }

    public String russianLabel() {
        return russianLabel;
    }

    public static CrmEntityType fromBitrixPlacement(String placement) {
        if (placement == null) {
            throw new IllegalArgumentException("Bitrix24 не передал PLACEMENT");
        }

        return switch (placement.trim().toUpperCase(Locale.ROOT)) {
            case "CRM_DEAL_DETAIL_ACTIVITY" -> DEAL;
            case "CRM_LEAD_DETAIL_ACTIVITY" -> LEAD;
            case "CRM_CONTACT_DETAIL_ACTIVITY" -> CONTACT;
            default -> throw new IllegalArgumentException(
                    "Неподдерживаемое место встраивания Bitrix24: " + placement);
        };
    }
}
