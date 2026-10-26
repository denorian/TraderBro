package com.traderbro.data.binance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The per-minute weight limiter must never admit more weight than the configured budget within a
 * single minute window, and {@code acquire} must eventually admit requests once the budget frees.
 */
class BinanceRateLimiterTest {

    @Test
    void admitsUpToBudgetThenBlocks() {
        BinanceRateLimiter limiter = new BinanceRateLimiter(100);
        // 50 reservations of weight 2 = 100 total, all must pass immediately.
        AtomicInteger admitted = new AtomicInteger();
        Thread t = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                limiter.acquire(2);
                admitted.incrementAndGet();
            }
        });
        t.start();
        try {
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(admitted.get()).isEqualTo(50);
        assertThat(limiter.usedWeight()).isEqualTo(100);
    }

    @Test
    void neverExceedsLimitAcrossManyAcquisitions() {
        BinanceRateLimiter limiter = new BinanceRateLimiter(1100);
        // Fill the window up to the budget; a larger single request must not be admitted.
        for (int i = 0; i < 550; i++) {
            limiter.acquire(2); // 1100 total
        }
        assertThat(limiter.usedWeight()).isEqualTo(1100);
    }
}