package com.traderbro.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Top-level application properties ({@code app.*}). */
@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** Sandbox mode. When false, live-trading ACK env is required at startup. */
    private boolean sandbox = true;
    /** Monetary scale for all trading money math (default 4). */
    private int moneyScale = 4;
    /** Max bars retained in memory for indicator windows (default 500). */
    private int maxBarsInMemory = 500;
    /** Parallelism for multi-instrument indicator computation. */
    private int indicatorThreads = 4;
    private History history = new History();

    @Getter
    @Setter
    public static class History {
        private boolean loadOnStartup = false;
        private int dailyYears = 3;
        private int intradayDays = 90;
        private String intradayInterval = "FIFTEEN_MIN";
    }
}