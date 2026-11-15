package com.traderbro.signalanalysis.api;

import com.traderbro.signalanalysis.service.TelegramAuthInputBridge;
import com.traderbro.signalanalysis.telegram.TelegramSessionManager;
import com.traderbro.signalanalysis.telegram.TelegramAuthState;
import java.net.InetSocketAddress;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * Local-only WebSocket endpoint for entering the Telegram login code and 2FA password from the
 * UI. Accepts connections from 127.0.0.1 only (never exposed outside the machine). A submitted
 * value is forwarded to the in-memory {@link TelegramAuthInputBridge} and consumed by the TDLib
 * {@code ClientInteraction} exactly once; it is not persisted or logged.
 */
@Slf4j
public class SignalAuthWebSocketHandler extends TextWebSocketHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TelegramAuthInputBridge bridge;
    private final TelegramSessionManager session;

    public SignalAuthWebSocketHandler(TelegramAuthInputBridge bridge,
                                      TelegramSessionManager session) {
        this.bridge = bridge;
        this.session = session;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession ws) throws Exception {
        InetSocketAddress remote = ws.getRemoteAddress();
        if (remote == null || !remote.getAddress().isLoopbackAddress()) {
            log.warn("Rejected non-loopback WebSocket auth connection from {}", remote);
            ws.close(CloseStatus.POLICY_VIOLATION);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) throws Exception {
        InetSocketAddress remote = ws.getRemoteAddress();
        if (remote == null || !remote.getAddress().isLoopbackAddress()) {
            ws.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        TelegramAuthState state = session.get();
        JsonNode node;
        try {
            node = JSON.readTree(message.getPayload());
        } catch (Exception e) {
            ws.sendMessage(new TextMessage("{\"ok\":false,\"error\":\"BAD_JSON\"}"));
            return;
        }
        String type = node.path("type").asText();
        String value = node.path("value").asText();
        TelegramAuthInputBridge.Kind kind = switch (type) {
            case "code" -> TelegramAuthInputBridge.Kind.CODE;
            case "password" -> TelegramAuthInputBridge.Kind.PASSWORD;
            default -> null;
        };
        if (kind == null || value.isEmpty()) {
            ws.sendMessage(new TextMessage("{\"ok\":false,\"error\":\"EMPTY\"}"));
            return;
        }
        // The code is expected only while TDLib waits for it; the password only for WAIT_PASSWORD.
        TelegramAuthState expected = kind == TelegramAuthInputBridge.Kind.CODE
                ? TelegramAuthState.WAIT_CODE : TelegramAuthState.WAIT_PASSWORD;
        if (state != expected) {
            ws.sendMessage(new TextMessage("{\"ok\":false,\"error\":\"NOT_WAITING\"}"));
            return;
        }
        boolean accepted = bridge.submit(kind, value);
        ws.sendMessage(new TextMessage("{\"ok\":" + accepted + "}"));
        ws.close(CloseStatus.NORMAL);
    }
}