package com.traderbro.core.risk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskConfigTest {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    private RiskConfig config(List<RiskConfig.TradingWindow> futuresWindows) {
        return new RiskConfig(
                new BigDecimal("0.02"), new BigDecimal("0.10"), new BigDecimal("0.50"),
                10, LocalTime.of(10, 0), LocalTime.of(18, 40),
                false, Duration.ofSeconds(60), MSK, 4,
                futuresWindows, new BigDecimal("0.30"), 0);
    }

    @Test
    void inSessionDuringMainWindowOnWeekday() {
        assertTrue(config(List.of()).isInSession(ZonedDateTime.of(
                2026, 10, 26, 12, 0, 0, 0, MSK))); // Mon 12:00
    }

    @Test
    void notInSessionOutsideMainWindowOnWeekday() {
        assertFalse(config(List.of()).isInSession(ZonedDateTime.of(
                2026, 10, 26, 2, 0, 0, 0, MSK))); // Mon 02:00 (night)
        assertFalse(config(List.of()).isInSession(ZonedDateTime.of(
                2026, 10, 26, 20, 0, 0, 0, MSK))); // Mon 20:00 (after 18:40)
    }

    @Test
    void notInSessionOnWeekend() {
        assertFalse(config(List.of()).isInSession(ZonedDateTime.of(
                2026, 10, 25, 12, 0, 0, 0, MSK))); // Sun 12:00
    }

    @Test
    void inSessionWithinFuturesWindow() {
        RiskConfig config = config(List.of(new RiskConfig.TradingWindow(
                LocalTime.of(19, 0), LocalTime.of(23, 50))));
        assertTrue(config.isInSession(ZonedDateTime.of(
                2026, 10, 26, 20, 0, 0, 0, MSK))); // Mon 20:00 in futures window
    }
}