package com.traderbro.signalanalysis.telegram;

/** Safe authorization state of the personal Telegram session, surfaced to UI as an enum only. */
public enum TelegramAuthState {
    WAIT_TDLIB_PARAMETERS,
    WAIT_PHONE_NUMBER,
    WAIT_CODE,
    WAIT_PASSWORD,
    WAIT_OTHER_DEVICE_CONFIRMATION,
    READY,
    LOGGING_OUT,
    CLOSED,
    FATAL;

    public boolean isReady() {
        return this == READY;
    }

    /** Whether ingestion cannot proceed until interactive authorization is performed. */
    public boolean requiresInteraction() {
        return this == WAIT_PHONE_NUMBER || this == WAIT_CODE || this == WAIT_PASSWORD;
    }
}