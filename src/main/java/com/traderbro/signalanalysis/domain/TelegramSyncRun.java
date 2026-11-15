package com.traderbro.signalanalysis.domain;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/** Record of a sync run ({@code telegram_sync_runs}) for observability and resume. */
@Getter
@Setter
@Builder
public class TelegramSyncRun {

    private UUID id;
    private UUID channelId;
    private SyncRunType runType;
    private SyncRunStatus status;
    private Instant requestedAt;
    private Instant startedAt;
    private Instant finishedAt;
    private Long cursorMessageId;
    private int loadedCount;
    private int createdCount;
    private int editedCount;
    private String errorCode;
    private String errorMessage;
}