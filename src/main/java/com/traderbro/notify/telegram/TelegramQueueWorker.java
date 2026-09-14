package com.traderbro.notify.telegram;

import com.traderbro.core.event.TraderEvent;
import com.traderbro.notify.NotificationRateLimiter;
import com.traderbro.notify.NotificationRecord;
import com.traderbro.notify.NotificationStore;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Background worker that drains the persistent notification queue: formats each record,
 * respects the per-minute rate limit, sends via {@link TelegramSender} and marks it SENT or
 * FAILED. Retry uses backoff 5s / 30s / 5min; a Telegram 429 pauses by the server's
 * {@code retry_after}. Telegram unavailability never affects the trading loop (fire-and-forget).
 * On startup it naturally re-sends PENDING/FAILED records.
 */
@Slf4j
public class TelegramQueueWorker {

    private static final long[] BACKOFF_MILLIS = {5_000, 30_000, 300_000};

    private final NotificationStore store;
    private final TelegramSender sender;
    private final TelegramMessageFormatter formatter;
    private final String chatId;
    private final NotificationRateLimiter rateLimiter;
    private final int maxAttempts;
    private final Map<Long, Instant> retryAt = new ConcurrentHashMap<>();

    public TelegramQueueWorker(NotificationStore store, TelegramSender sender,
                               TelegramMessageFormatter formatter, String chatId,
                               int maxMessagesPerMinute, int maxAttempts) {
        this.store = store;
        this.sender = sender;
        this.formatter = formatter;
        this.chatId = chatId;
        this.rateLimiter = new NotificationRateLimiter(maxMessagesPerMinute);
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${telegram.worker-poll-ms:5000}")
    public void drain() {
        for (NotificationRecord r : store.findPendingForRetry(maxAttempts, 20)) {
            Instant blockedUntil = retryAt.get(r.getId());
            if (blockedUntil != null && blockedUntil.isAfter(Instant.now())) {
                continue;
            }
            process(r);
        }
    }

    private void process(NotificationRecord r) {
        if (!rateLimiter.allow()) {
            long wait = rateLimiter.millisUntilNextSlot();
            log.debug("rate limit reached; next slot in {}ms", wait);
            return;
        }
        String html = formatter.format(toEvent(r));
        TelegramSender.SendResult result = sender.sendHtml(chatId, html);
        if (result.ok()) {
            store.markSent(r.getId(), result.messageId(), Instant.now());
            retryAt.remove(r.getId());
        } else if (result.retryAfterSeconds() > 0) {
            // Telegram 429: honest pause by the server-provided retry_after.
            store.incrementAttempt(r.getId());
            retryAt.put(r.getId(), Instant.now().plusSeconds(result.retryAfterSeconds()));
            log.warn("telegram 429, pausing notification {} for {}s", r.getId(), result.retryAfterSeconds());
        } else {
            int attempt = r.getAttempts() + 1;
            store.incrementAttempt(r.getId());
            if (attempt >= maxAttempts) {
                store.markFailed(r.getId());
                retryAt.remove(r.getId());
                log.error("notification {} failed after {} attempts", r.getId(), attempt);
            } else {
                long backoff = BACKOFF_MILLIS[Math.min(attempt - 1, BACKOFF_MILLIS.length - 1)];
                retryAt.put(r.getId(), Instant.now().plusMillis(backoff));
                log.warn("notification {} send failed, retry {} in {}ms", r.getId(), attempt, backoff);
            }
        }
    }

    private TraderEvent toEvent(NotificationRecord r) {
        return new TraderEvent(r.getType(), r.getLevel(), r.getCreatedAt(), r.getPayload());
    }
}