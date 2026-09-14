package com.traderbro.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotificationDeduplicatorTest {

    @Test
    void suppressesIdenticalKeyWithinWindow() {
        NotificationDeduplicator dedup = new NotificationDeduplicator(Duration.ofMinutes(15));
        Map<String, Object> payload = new HashMap<>();
        payload.put("strategyId", "S");
        payload.put("figi", "F");
        payload.put("reason", "DailyLossLimit");
        String key = NotificationDeduplicator.keyFor("SIGNAL_REJECTED", payload, "strategyId", "figi", "reason");

        assertThat(dedup.shouldSend(key)).isTrue();
        assertThat(dedup.shouldSend(key)).isFalse(); // suppressed within window
    }

    @Test
    void allowsDifferentKeys() {
        NotificationDeduplicator dedup = new NotificationDeduplicator(Duration.ofMinutes(15));
        assertThat(dedup.shouldSend("a")).isTrue();
        assertThat(dedup.shouldSend("b")).isTrue();
    }

    @Test
    void nullKeyAlwaysSends() {
        NotificationDeduplicator dedup = new NotificationDeduplicator(Duration.ofMinutes(15));
        assertThat(dedup.shouldSend(null)).isTrue();
        assertThat(dedup.shouldSend(null)).isTrue();
    }
}