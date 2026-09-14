package com.traderbro.notify;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Suppresses repeated identical notifications (e.g. SIGNAL_REJECTED) within a time window,
 * keyed by a dedup key derived from the event. Thread-safe.
 */
public class NotificationDeduplicator {

    private final Duration window;
    private final Map<String, Instant> lastSent = new ConcurrentHashMap<>();

    public NotificationDeduplicator(Duration window) {
        this.window = window;
    }

    /** True if the event should be sent (not suppressed by the window). */
    public boolean shouldSend(String dedupKey) {
        if (dedupKey == null) {
            return true;
        }
        Instant now = Instant.now();
        Instant last = lastSent.get(dedupKey);
        if (last != null && last.plus(window).isAfter(now)) {
            return false;
        }
        lastSent.put(dedupKey, now);
        return true;
    }

    /** Derives a stable dedup key from the event type and selected payload fields. */
    public static String keyFor(String type, Map<String, Object> payload, String... fields) {
        StringBuilder sb = new StringBuilder(type);
        for (String f : fields) {
            sb.append('|').append(payload.get(f));
        }
        return sb.toString();
    }
}