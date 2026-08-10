package ru.abs7.videooffer.bitrix.mobile.upload;

public enum MobileVideoSourceKind {
    RECORDING,
    FILE,
    MERGED;

    public static MobileVideoSourceKind orDefault(MobileVideoSourceKind value) {
        return value == null ? RECORDING : value;
    }
}
