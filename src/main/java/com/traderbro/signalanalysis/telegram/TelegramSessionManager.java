package com.traderbro.signalanalysis.telegram;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Holds the current authorization state of the personal Telegram session. Updated by the TDLib
 * adapter via {@link TelegramUpdate.AuthorizationState}; read by the ingestion lifecycle, health
 * indicator and REST status endpoint. Safe enum only — no phone, code or session details here.
 */
@Component
public class TelegramSessionManager {

    private final AtomicReference<TelegramAuthState> state =
            new AtomicReference<>(TelegramAuthState.WAIT_TDLIB_PARAMETERS);

    public void set(TelegramAuthState s) {
        state.set(s);
    }

    public TelegramAuthState get() {
        return state.get();
    }

    public boolean isReady() {
        return state.get().isReady();
    }
}