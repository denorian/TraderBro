package com.traderbro.core.risk;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
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
}