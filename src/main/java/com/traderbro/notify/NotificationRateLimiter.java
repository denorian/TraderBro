package com.traderbro.notify;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Sliding-window rate limiter for outbound messages (Telegram API limits). Thread-safe.
 */
public class NotificationRateLimiter {

    private final int maxPerWindow;
    private final long windowMillis;
    private final Deque<Long> timestamps = new ArrayDeque<>();

    public NotificationRateLimiter(int maxPerMinute) {
        this.maxPerWindow = maxPerMinute;
        this.windowMillis = 60_000;
    }

    /** True if a new message is allowed under the per-minute cap; records the slot if allowed. */
    public synchronized boolean allow() {
        long now = System.currentTimeMillis();
        while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= windowMillis) {
            timestamps.pollFirst();
        }
        if (timestamps.size() >= maxPerWindow) {
            return false;
        }
        timestamps.addLast(now);
        return true;
    }

    /** Milliseconds until the next slot frees (for honest 429/rate-limit backoff). */
    public synchronized long millisUntilNextSlot() {
        if (timestamps.isEmpty()) {
            return 0;
        }
        return Math.max(0, windowMillis - (System.currentTimeMillis() - timestamps.peekFirst()));
    }
}