package com.traderbro.signalanalysis.domain;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/** Append-only version of a message ({@code telegram_message_versions}). Never mutated/removed. */
@Getter
@Setter
@Builder
public class TelegramMessageVersion {

    private UUID id;
    private UUID messageId;
    private int versionNo;
    private ChangeType changeType;
    private Instant sourceEditedAt;
    private Instant observedAt;
    private String contentType;
    private String textContent;
    private String formattedEntities;
    private String mediaMetadata;
    private String forwardMetadata;
    private String contentHash;
    private UUID sourceEventId;
    private Instant createdAt;
}