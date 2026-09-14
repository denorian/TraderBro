package com.traderbro.notify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NotificationRateLimiterTest {

    @Test
    void capsAtMaxPerMinute() {
        NotificationRateLimiter limiter = new NotificationRateLimiter(2);
        assertThat(limiter.allow()).isTrue();
        assertThat(limiter.allow()).isTrue();
        assertThat(limiter.allow()).isFalse(); // over cap
    }

    @Test
    void reportsMillisUntilNextSlot() {
        NotificationRateLimiter limiter = new NotificationRateLimiter(1);
        limiter.allow();
        long wait = limiter.millisUntilNextSlot();
        assertThat(wait).isBetween(1L, 60_000L);
    }
}