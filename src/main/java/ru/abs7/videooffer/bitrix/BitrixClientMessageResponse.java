package ru.abs7.videooffer.bitrix;

public record BitrixClientMessageResponse(
        String message,
        Long responsibleId,
        String responsibleName,
        boolean contactsAvailable,
        String warning) {

    public static BitrixClientMessageResponse available(
            BitrixResponsibleEmployeeService.ClientMessageTemplate template) {
        BitrixResponsibleEmployeeService.ResponsibleEmployee responsible = template.responsible();
        return new BitrixClientMessageResponse(
                template.message(),
                responsible.id(),
                responsible.fullName(),
                true,
                null);
    }

    public static BitrixClientMessageResponse fallback(String message, String warning) {
        return new BitrixClientMessageResponse(message, null, null, false, warning);
    }
}
