package ru.abs7.videooffer.bitrix;

import org.junit.jupiter.api.Test;
import ru.abs7.videooffer.tenant.TenantAccessService;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BitrixResponsibleEmployeeServiceTest {
    @Test
    void clientContactTemplateAutofillIsNoLongerPartOfResponsibleService() {
        boolean legacyTemplateBuilderExists = Arrays.stream(BitrixResponsibleEmployeeService.class.getDeclaredMethods())
                .map(Method::getName)
                .anyMatch(name -> name.equals("buildClientMessageTemplate") || name.equals("buildTemplate"));

        assertFalse(legacyTemplateBuilderExists);
        assertTrue(TenantAccessService.VIDEO_URL_PLACEHOLDER.contains("VIDEO_URL"));
    }
}
