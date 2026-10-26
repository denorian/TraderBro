package com.traderbro.data.binance;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Fixed one-minute-window weight limiter mirroring Binance's REST weight budget
 * ({@code X-MBX-USED-WEIGHT-1M}). {@code acquire(weight)} blocks the caller until the requested
 * weight fits in the current minute, rolling the window once a minute has elapsed.
 */
public class BinanceRateLimiter {

    private final int limitPerMin;
    private final ReentrantLock lock = new ReentrantLock();
    private long windowStartMs = System.currentTimeMillis();
    private int used = 0;

    public BinanceRateLimiter(int limitPerMin) {
        this.limitPerMin = limitPerMin;
    }

    /**
     * Reserves {@code weight} weight units, sleeping until they fit within the per-minute budget.
     * Returns once the reservation succeeds (or the thread is interrupted).
     */
    public void acquire(int weight) {
        long sleepMs;
        while (true) {
            lock.lock();
            try {
                long now = System.currentTimeMillis();
                if (now - windowStartMs >= 60_000L) {
                    windowStartMs = now;
                    used = 0;
                }
                if (used + weight <= limitPerMin) {
                    used += weight;
                    return;
                }
                sleepMs = 60_000L - (now - windowStartMs);
            } finally {
                lock.unlock();
            }
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Current per-minute usage (for diagnostics). */
    public int usedWeight() {
        lock.lock();
        try {
            return used;
        } finally {
            lock.unlock();
        }
    }
}