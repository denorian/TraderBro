package com.traderbro.core.event;

import java.time.Instant;
import java.util.Map;

/**
 * A structured trading event emitted by the engine, decoupled from any delivery channel.
 * The {@code notify} package subscribes to these, persists them and (optionally) sends them
 * to Telegram. Payload keys are formatter-specific and documented per notification type.
 */
public record TraderEvent(NotificationType type, NotificationLevel level, Instant at,
                          Map<String, Object> payload) {

    public static TraderEvent of(NotificationType type, NotificationLevel level, Map<String, Object> payload) {
        return new TraderEvent(type, level, Instant.now(), payload);
    }
}