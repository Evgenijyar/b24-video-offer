package ru.abs7.videooffer.bitrix;

public record BitrixClientMessageResponse(
        String message,
        String accompanyingText,
        Long employeeId,
        String employeeName,
        String warning) {

    public static BitrixClientMessageResponse available(
            String message,
            String accompanyingText,
            Long employeeId,
            String employeeName) {
        return new BitrixClientMessageResponse(message, accompanyingText, employeeId, employeeName, null);
    }
}
