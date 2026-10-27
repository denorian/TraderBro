package com.traderbro.core.risk;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import lombok.Value;

/**
 * Risk limits and trading-window configuration. Immutable snapshot taken from configuration
 * at startup/hot-reload. All fractions are absolute (0.02 = 2%).
 */
@Value
public class RiskConfig {

    /** A half-open trading session interval [start, end). */
    @Value
    public static class TradingWindow {
        LocalTime start;
        LocalTime end;

        public boolean contains(LocalTime t) {
            return !t.isBefore(start) && t.isBefore(end);
        }
    }

    BigDecimal dailyLossLimitPct;
    BigDecimal positionLimitPct;
    BigDecimal exposureLimitPct;
    int maxOrderRatePerMinute;
    LocalTime tradingWindowStart;
    LocalTime tradingWindowEnd;
    boolean allowWeekendTrading;
    Duration maxStreamLag;
    ZoneId tradingZone;
    int moneyScale;

    /** Trading windows for futures (evening session + clearing breaks). */
    List<TradingWindow> futuresTradingWindows;
    /** Max total futures margin (GO) as a fraction of portfolio. */
    BigDecimal futuresMarginLimitPct;
    /** No new futures positions within this many days before expiry. */
    int noNewPositionsDaysBeforeExpiry;

    /**
     * True while the exchange is (expected to be) trading in the configured zone: a weekday and
     * the local time within the main trading window or any futures window. Outside trading hours
     * a live candle stream legitimately delivers no data, so staleness checks must not treat that
     * as data loss.
     */
    public boolean isInSession(ZonedDateTime now) {
        if (!allowWeekendTrading) {
            DayOfWeek dow = now.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
                return false;
            }
        }
        LocalTime t = now.toLocalTime();
        if (within(t, tradingWindowStart, tradingWindowEnd)) {
            return true;
        }
        for (TradingWindow window : futuresTradingWindows) {
            if (window.contains(t)) {
                return true;
            }
        }
        return false;
    }

    private static boolean within(LocalTime t, LocalTime start, LocalTime end) {
        return !t.isBefore(start) && t.isBefore(end);
    }
}