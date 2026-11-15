package com.traderbro.signalanalysis.domain;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/**
 * Metadata row for a Telegram broadcast channel ({@code telegram_channels}). Metadata is kept
 * for every channel the account is subscribed to (Main or Archive), independent of monitoring.
 * {@code monitoringEnabled} is a user intent flag; effective collection stops when the
 * subscription is lost, but the flag is preserved.
 */
@Getter
@Setter
@Builder
public class TelegramChannel {

    private UUID id;
    private long telegramChatId;
    private Long telegramSupergroupId;
    private String title;
    private String username;
    private boolean channel;
    private boolean subscribed;
    private boolean archived;
    /** MEMBER / ADMINISTRATOR / CREATOR / RESTRICTED / LEFT / BANNED. */
    private String membershipStatus;
    private boolean monitoringEnabled;
    private Instant monitoringUpdatedAt;
    private Instant firstSeenAt;
    private Instant lastSeenAt;
    private Instant subscriptionSyncedAt;
    private Instant createdAt;
    private Instant updatedAt;
}