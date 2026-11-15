package com.traderbro.signalanalysis.domain;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/** Per-channel history sync progress ({@code telegram_channel_sync_state}). */
@Getter
@Setter
@Builder
public class TelegramChannelSyncState {

    private UUID channelId;
    private Long oldestLoadedMessageId;
    private Instant oldestLoadedMessageSentAt;
    private Long newestLoadedMessageId;
    private boolean fullBackfillCompleted;
    private Long lastHistoryRequestMessageId;
    private Instant lastLiveEventAt;
    private Instant lastTailSyncAt;
    private Instant lastSuccessfulSyncAt;
    private String lastErrorCode;
    /** Sanitized description only — never message content. */
    private String lastErrorMessage;
    private int syncAttempts;
    private Instant updatedAt;
}