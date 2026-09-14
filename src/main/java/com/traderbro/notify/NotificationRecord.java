package com.traderbro.notify;

import com.traderbro.core.event.NotificationLevel;
import com.traderbro.core.event.NotificationType;
import java.time.Instant;
import java.util.Map;
import lombok.Builder;
import lombok.Value;

/**
 * A persisted outbound notification. Lives in {@code notify} (not core) so the trading loop
 * stays delivery-agnostic; {@code NotificationService} produces these and the queue worker
 * sends them.
 */
@Value
@Builder
public class NotificationRecord {

    long id;
    NotificationType type;
    NotificationLevel level;
    Map<String, Object> payload;
    NotificationStatus status;
    int attempts;
    Instant createdAt;
    Instant sentAt;
    String telegramMessageId;
    String dedupKey;
}