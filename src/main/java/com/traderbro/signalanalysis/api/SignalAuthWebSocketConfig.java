package com.traderbro.signalanalysis.api;

import com.traderbro.signalanalysis.service.TelegramAuthInputBridge;
import com.traderbro.signalanalysis.telegram.TelegramSessionManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the local login-code/2FA WebSocket endpoint. The handler itself rejects any
 * non-loopback connection, so the channel never leaves the machine. Always registered (works even
 * when ingestion is off) — the handler only acts while TDLib waits for a code or password.
 */
@Configuration
@EnableWebSocket
public class SignalAuthWebSocketConfig implements WebSocketConfigurer {

    private final TelegramAuthInputBridge bridge;
    private final TelegramSessionManager session;

    public SignalAuthWebSocketConfig(TelegramAuthInputBridge bridge,
                                     TelegramSessionManager session) {
        this.bridge = bridge;
        this.session = session;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // The handler only accepts loopback connections (never exposed outside the machine), but
        // the browser-originated UI (Angular dev server) is cross-origin vs the backend, so the
        // WebSocket handshake must allow the frontend origin. Loopback enforcement is in the
        // handler itself (see SignalAuthWebSocketHandler).
        registry.addHandler(new SignalAuthWebSocketHandler(bridge, session),
                        "/ws/signal-analysis/auth")
                .setAllowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*");
    }
}