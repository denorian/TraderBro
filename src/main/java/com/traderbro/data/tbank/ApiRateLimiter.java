package com.traderbro.data.tbank;

/**
 * A fixed-window (1-minute) rate limiter used to keep outgoing broker API calls under the
 * per-minute quota (T-Bank returns {@code 80002} "превышен лимит запросов в минуту" when
 * exceeded). {@link #acquire()} blocks the caller until a slot in the current minute is free,
 * then releases it — so a heavy backfill slows down instead of dropping instruments.
 */
public final class ApiRateLimiter {

    private static final long WINDOW_MS = 60_000;

    private final int maxPerMinute;
    private final Object lock = new Object();
    private long windowStart = System.currentTimeMillis();
    private int used;

    public ApiRateLimiter(int maxPerMinute) {
        this.maxPerMinute = Math.max(1, maxPerMinute);
    }

    /** Blocks until a request slot is available, then occupies it. */
    public void acquire() {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            if (now - windowStart >= WINDOW_MS) {
                windowStart = now;
                used = 0;
            }
            if (used >= maxPerMinute) {
                long wait = WINDOW_MS - (now - windowStart);
                try {
                    lock.wait(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    // Treat an interrupt as an immediate slot so the caller can bail out.
                    return;
                }
                windowStart = System.currentTimeMillis();
                used = 0;
            }
            used++;
        }
    }
}