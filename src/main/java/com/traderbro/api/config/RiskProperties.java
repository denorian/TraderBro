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
    private String tradingZone = "Europe/Moscow";
    /** Futures-specific risk knobs. */
    private Futures futures = new Futures();
    /** Per-instrument-class trading windows ("HH:mm-HH:mm[,HH:mm-HH:mm...]"). */
    private TradingWindows tradingWindows = new TradingWindows();

    @Getter
    @Setter
    public static class Futures {
        /** Max total futures margin (GO) as a fraction of portfolio, percent (default 30). */
        private int maxMarginPct = 30;
    }

    @Getter
    @Setter
    public static class TradingWindows {
        private String shares = "10:00-18:40";
        private String futures = "10:00-14:00,14:05-18:45,19:00-23:50";
    }
}