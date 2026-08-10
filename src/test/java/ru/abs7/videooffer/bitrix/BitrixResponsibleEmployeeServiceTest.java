package ru.abs7.videooffer.bitrix;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BitrixResponsibleEmployeeServiceTest {
    private final BitrixResponsibleEmployeeService service = new BitrixResponsibleEmployeeService(null);

    @Test
    void buildsEditableClientMessageWithEmployeeContacts() {
        var employee = new BitrixResponsibleEmployeeService.ResponsibleEmployee(
                42L,
                "Иван Иванов",
                "+7 999 111-22-33",
                "+7 495 100-20-30",
                "+7 999 111-22-33",
                "ivan@example.com");

        String message = service.buildTemplate(employee);

        assertTrue(message.contains(BitrixResponsibleEmployeeService.VIDEO_URL_PLACEHOLDER));
        assertTrue(message.contains("Иван Иванов"));
        assertTrue(message.contains("Мобильный телефон: +7 999 111-22-33"));
        assertTrue(message.contains("WhatsApp: +7 999 111-22-33"));
        assertTrue(message.contains("MAX: +7 999 111-22-33"));
        assertTrue(message.contains("Telegram: +7 999 111-22-33"));
        assertTrue(message.contains("Рабочий телефон: +7 495 100-20-30"));
        assertTrue(message.contains("E-mail: ivan@example.com"));
        assertFalse(message.contains("\nТелефон: +7 999 111-22-33"),
                "duplicate personal phone must not be printed twice");
    }
}
