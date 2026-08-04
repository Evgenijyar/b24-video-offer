package ru.abs7.videooffer.bitrix;

public class BitrixRestException extends RuntimeException {
    private final String errorCode;

    public BitrixRestException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
