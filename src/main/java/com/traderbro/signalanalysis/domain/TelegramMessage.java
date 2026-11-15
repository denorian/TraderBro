package com.traderbro.signalanalysis.domain;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/** Current projection of a message ({@code telegram_messages}). Content is never deleted. */
@Getter
@Setter
@Builder
public class TelegramMessage {

    private UUID id;
    private UUID channelId;
    private long telegramMessageId;
    private String senderType;
    private Long senderId;
    private String authorSignature;
    private Instant sentAt;
    private Instant sourceEditedAt;
    private Long replyToMessageId;
    private String forwardMetadata;
    private String contentType;
    private String textContent;
    private String formattedEntities;
    private String mediaMetadata;
    private String contentHash;
    private int currentVersionNo;
    private AvailabilityStatus availabilityStatus;
    private Instant firstObservedAt;
    private Instant lastObservedAt;
    private Instant deletionObservedAt;
    private boolean permanentlyDeleted;
    private Instant createdAt;
    private Instant updatedAt;
}