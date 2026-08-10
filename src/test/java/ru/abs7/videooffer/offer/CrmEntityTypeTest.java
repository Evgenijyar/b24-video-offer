package ru.abs7.videooffer.offer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CrmEntityTypeTest {
    @Test
    void resolvesSupportedBitrixPlacements() {
        assertEquals(CrmEntityType.DEAL,
                CrmEntityType.fromBitrixPlacement("CRM_DEAL_DETAIL_ACTIVITY"));
        assertEquals(CrmEntityType.LEAD,
                CrmEntityType.fromBitrixPlacement("CRM_LEAD_DETAIL_ACTIVITY"));
        assertEquals(CrmEntityType.CONTACT,
                CrmEntityType.fromBitrixPlacement("CRM_CONTACT_DETAIL_ACTIVITY"));

        assertEquals(CrmEntityType.DEAL,
                CrmEntityType.fromBitrixPlacement("CRM_DEAL_DETAIL_TOOLBAR"));
        assertEquals(CrmEntityType.LEAD,
                CrmEntityType.fromBitrixPlacement("CRM_LEAD_DETAIL_TOOLBAR"));
        assertEquals(CrmEntityType.CONTACT,
                CrmEntityType.fromBitrixPlacement("CRM_CONTACT_DETAIL_TOOLBAR"));

        assertEquals(CrmEntityType.DEAL,
                CrmEntityType.fromBitrixPlacement("CRM_DEAL_DETAIL_TAB"));
        assertEquals(CrmEntityType.LEAD,
                CrmEntityType.fromBitrixPlacement("CRM_LEAD_DETAIL_TAB"));
        assertEquals(CrmEntityType.CONTACT,
                CrmEntityType.fromBitrixPlacement("CRM_CONTACT_DETAIL_TAB"));
    }

    @Test
    void exposesBitrixSystemEntityTypeIds() {
        assertEquals(1, CrmEntityType.LEAD.bitrixEntityTypeId());
        assertEquals(2, CrmEntityType.DEAL.bitrixEntityTypeId());
        assertEquals(3, CrmEntityType.CONTACT.bitrixEntityTypeId());
    }

    @Test
    void rejectsUnknownPlacement() {
        assertThrows(IllegalArgumentException.class,
                () -> CrmEntityType.fromBitrixPlacement("CRM_COMPANY_DETAIL_ACTIVITY"));
    }
}
