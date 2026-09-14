package com.traderbro.notify;

import java.time.Instant;
import java.util.List;

/**
 * Persistence contract for the notification queue, implemented by the storage layer.
 * Keeps {@code notify} independent of Spring Data types.
 */
public interface NotificationStore {

    /** Inserts a notification as PENDING and returns its id. */
    long insert(NotificationRecord record);

    void markSent(long id, String telegramMessageId, Instant sentAt);

    void markFailed(long id);

    void incrementAttempt(long id);

    /** Notifications to (re)send: PENDING or FAILED with attempts below the max. */
    List<NotificationRecord> findPendingForRetry(int maxAttempts, int limit);
}