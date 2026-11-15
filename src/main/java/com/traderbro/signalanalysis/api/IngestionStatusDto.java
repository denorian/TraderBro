package com.traderbro.signalanalysis.api;

import com.traderbro.signalanalysis.telegram.TelegramAuthState;
import java.time.Instant;

/**
 * Safe ingestion status: no phone, api hash, session path, encryption key or stack trace. These
 * fields are the only Telegram-related details exposed to the UI/API.
 */
public record IngestionStatusDto(
        boolean enabled,
        TelegramAuthState authorizationState,
        boolean ingestionActive,
        Instant lastIngestionHeartbeatAt,
        Instant lastDiscoveryAt,
        long pendingEventCount,
        long failedEventCount) {
}