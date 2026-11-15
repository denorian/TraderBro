package com.traderbro.signalanalysis.service;

import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Component;

/**
 * One-shot bridge for the interactive Telegram login inputs (code and 2FA password), delivered
 * from the local WebSocket (bound to 127.0.0.1 only) to the TDLib {@code ClientInteraction}
 * flow. Values are never persisted, logged or transmitted over REST; each lives only in its
 * in-memory future until TDLib consumes it once.
 */
@Component
public class TelegramAuthInputBridge {

    private CompletableFuture<String> code = new CompletableFuture<>();
    private CompletableFuture<String> password = new CompletableFuture<>();

    /** Called by {@code ClientInteraction.onParameterRequest} for {@code ASK_CODE}. */
    public synchronized CompletableFuture<String> awaitCode() {
        if (code.isDone()) {
            code = new CompletableFuture<>();
        }
        return code;
    }

    /** Called by {@code ClientInteraction.onParameterRequest} for {@code ASK_PASSWORD}. */
    public synchronized CompletableFuture<String> awaitPassword() {
        if (password.isDone()) {
            password = new CompletableFuture<>();
        }
        return password;
    }

    /** Called by the WebSocket handler when the user submits a value from the UI. */
    public boolean submit(Kind kind, String value) {
        synchronized (this) {
            CompletableFuture<String> target = kind == Kind.CODE ? code : password;
            if (target.isDone()) {
                return false;
            }
            target.complete(value);
            return true;
        }
    }

    /** Reject any pending request (e.g. session closed before the value was entered). */
    public void cancel() {
        synchronized (this) {
            code.cancel(false);
            password.cancel(false);
            code = new CompletableFuture<>();
            password = new CompletableFuture<>();
        }
    }

    /** Which interactive auth input is being supplied. */
    public enum Kind {
        CODE,
        PASSWORD
    }
}