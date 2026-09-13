package com.traderbro.core.risk;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import lombok.Value;

/**
 * Risk limits and trading-window configuration. Immutable snapshot taken from configuration
 * at startup/hot-reload. All fractions are absolute (0.02 = 2%).
 */
@Value
public class RiskConfig {

    /** Max tolerable daily loss as a fraction of portfolio value (default 0.02). */
    BigDecimal dailyLossLimitPct;
    /** Max position per instrument as a fraction of portfolio value (default 0.10). */
    BigDecimal positionLimitPct;
    /** Max total exposure as a fraction of portfolio value (default 0.50). */
    BigDecimal exposureLimitPct;
    /** Max orders per minute (default 10). */
    int maxOrderRatePerMinute;
    /** Trading window start (Europe/Moscow), inclusive (default 10:00). */
    LocalTime tradingWindowStart;
    /** Trading window end (Europe/Moscow), exclusive (default 18:40). */
    LocalTime tradingWindowEnd;
    /** Whether trading is allowed on weekends (default false). */
    boolean allowWeekendTrading;
    /** Max tolerable stream lag before the data-freshness rule blocks trading (default 60s). */
    java.time.Duration maxStreamLag;
    /** Time zone used for trading-window checks. */
    ZoneId tradingZone;
    /** Monetary scale used for rounding notional/exposure math. */
    int moneyScale;
}