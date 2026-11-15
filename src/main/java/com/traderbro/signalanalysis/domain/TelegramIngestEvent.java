package com.traderbro.signalanalysis.domain;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/** Durable inbox event ({@code telegram_ingest_events}). At-least-once, idempotent by dedup_key. */
@Getter
@Setter
@Builder
public class TelegramIngestEvent {

    private Long receivedSeq;
    private UUID id;
    private String eventType;
    private long telegramChatId;
    private Long telegramMessageId;
    private String dedupKey;
    private String payload;
    private Instant receivedAt;
    private IngestEventStatus status;
    private int attempts;
    private Instant availableAt;
    private Instant lockedAt;
    private String lockedBy;
    private Instant processedAt;
    private String lastError;
}