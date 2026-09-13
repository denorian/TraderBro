package com.traderbro.api.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Risk limits and trading-window configuration ({@code risk.*}). */
@Getter
@Setter
@ConfigurationProperties(prefix = "risk")
public class RiskProperties {

    private BigDecimal dailyLossLimitPct = new BigDecimal("0.02");
    private BigDecimal positionLimitPct = new BigDecimal("0.10");
    private BigDecimal exposureLimitPct = new BigDecimal("0.50");
    private int maxOrderRatePerMinute = 10;
    private LocalTime tradingWindowStart = LocalTime.of(10, 0);
    private LocalTime tradingWindowEnd = LocalTime.of(18, 40);
    private boolean allowWeekendTrading = false;
    private Duration maxStreamLag = Duration.ofSeconds(60);
    /** Zone used for trading-window checks. */
    private String tradingZone = "Europe/Moscow";
}