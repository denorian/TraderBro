package com.traderbro.signalanalysis.api;

import com.traderbro.signalanalysis.domain.SignalIngestionStatus;
import java.time.Instant;

/**
 * Channel row for the UI list. Telegram IDs are carried as strings so JavaScript never loses
 * precision on int64. Message content is never included.
 */
public record SignalChannelDto(
        String id,
        String telegramChatId,
        String title,
        String username,
        boolean subscribed,
        boolean archived,
        String membershipStatus,
        boolean monitoringEnabled,
        SignalIngestionStatus ingestionStatus,
        Instant lastMessageAt,
        Instant lastSyncAt,
        long messageCount,
        long editedMessageCount,
        long deletedMessageCount,
        String lastErrorCode) {
}