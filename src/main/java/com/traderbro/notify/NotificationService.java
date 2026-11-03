package com.traderbro.notify;

import com.traderbro.core.event.NotificationLevel;
import com.traderbro.core.event.NotificationType;
import com.traderbro.core.event.TraderEvent;
import com.traderbro.core.event.TraderEventPublisher;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

/**
 * Central notification gateway implementing {@link TraderEventPublisher}. Applies the
 * {@code telegram.enabled} switch and {@code min-level} filter, deduplicates noisy events, and
 * persists each notification as PENDING for the queue worker. When disabled it silently skips
 * and counts the event (exposed for the {@code notifications.skipped} metric).
 */
@Slf4j
public class NotificationService implements TraderEventPublisher {

    private final boolean enabled;
    private final NotificationLevel minLevel;
    private final NotificationStore store;
    private final NotificationDeduplicator rejectDedup;
    private final NotificationDeduplicator partialDedup;
    private final AtomicLong skipped = new AtomicLong();

    public NotificationService(boolean enabled, NotificationLevel minLevel, NotificationStore store) {
        this.enabled = enabled;
        this.minLevel = minLevel;
        this.store = store;
        // SIGNAL_REJECTED: at most once per 15 min per strategy+instrument+reason.
        this.rejectDedup = new NotificationDeduplicator(Duration.ofMinutes(15));
        // ORDER_PARTIALLY_FILLED: aggregate at most once per minute per order.
        this.partialDedup = new NotificationDeduplicator(Duration.ofMinutes(1));
    }

    @Override
    public void publish(TraderEvent event) {
        if (!enabled) {
            skipped.incrementAndGet();
            return;
        }
        if (event.level().ordinal() < minLevel.ordinal()) {
            skipped.incrementAndGet();
            return;
        }
        String dedupKey = dedupKeyFor(event);
        if (!isDedupOk(event.type(), dedupKey)) {
            skipped.incrementAndGet();
            return;
        }
        // Permanent dedup for deterministic (non-transient) events: a divergence/angulation
        // carries a stable natural key, so once queued/sent it must never be re-sent — even
        // across restarts or catch-up rescans. In-memory windows alone would forget on restart.
        if (isPermanentKey(event.type()) && store.existsWithDedupKey(dedupKey)) {
            skipped.incrementAndGet();
            return;
        }
        NotificationRecord record = NotificationRecord.builder()
                .type(event.type())
                .level(event.level())
                .payload(event.payload())
                .status(NotificationStatus.PENDING)
                .attempts(0)
                .createdAt(event.at())
                .dedupKey(dedupKey)
                .build();
        long id = store.insert(record);
        log.debug("notification queued id={} type={} level={}", id, event.type(), event.level());
    }

    private boolean isDedupOk(NotificationType type, String dedupKey) {
        if (type == NotificationType.SIGNAL_REJECTED) {
            return rejectDedup.shouldSend(dedupKey);
        }
        if (type == NotificationType.ORDER_PARTIALLY_FILLED) {
            return partialDedup.shouldSend(dedupKey);
        }
        return true;
    }

    /** Event types whose dedup key is a stable natural key, so they are permanently deduplicated
     * by the DB across restarts. Transient events (signals, fills) are not. */
    private static boolean isPermanentKey(NotificationType type) {
        return switch (type) {
            case AO_DIVERGENCE_DETECTED, AO_DIVERGENCE_CONFIRMED, ANGULATION_DETECTED -> true;
            default -> false;
        };
    }

    private String dedupKeyFor(TraderEvent e) {
        return switch (e.type()) {
            case SIGNAL_REJECTED -> NotificationDeduplicator.keyFor(
                    e.type().name(), e.payload(), "strategyId", "figi", "reason");
            case ORDER_PARTIALLY_FILLED -> NotificationDeduplicator.keyFor(
                    e.type().name(), e.payload(), "orderId");
            case AO_DIVERGENCE_DETECTED, AO_DIVERGENCE_CONFIRMED, ANGULATION_DETECTED ->
                    NotificationDeduplicator.keyFor(
                            e.type().name(), e.payload(), "figi", "interval", "aoPeaks",
                            "priceAtPeaks");
            default -> null;
        };
    }

    /** Number of events skipped (disabled / below level / deduplicated). */
    public long skippedCount() {
        return skipped.get();
    }
}