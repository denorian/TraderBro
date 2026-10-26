package com.traderbro.api.config;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Crypto (Binance spot) market-data configuration ({@code crypto.*}). Crypto is read-only in this
 * stage: data, analytics and backtesting only; execution is a stage-4 TODO.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "crypto")
public class CryptoProperties {

    /** Master switch; when false no Binance client/loaders are created. */
    private boolean enabled = true;
    private Binance binance = new Binance();
    /** Spot pairs tracked (top-5 by market cap, excluding stablecoins). */
    private List<String> pairs = List.of("BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT");
    /** Timeframes used for streaming and indicator snapshots. */
    private List<String> timeframes = List.of("FIFTEEN_MIN", "ONE_HOUR", "ONE_DAY");
    private History history = new History();
    /** Default Binance spot taker commission, in percent (0.1 = 0.1%). */
    private double takerFeePct = 0.1;
    /** Default slippage in basis points for crypto backtests. */
    private int slippageBps = 5;
    private CoinGecko coingecko = new CoinGecko();

    @Getter
    @Setter
    public static class Binance {
        private String baseUrl = "https://api.binance.com";
        private String wsUrl = "wss://stream.binance.com:9443";
        /** REST weight budget per minute (1200 limit minus margin). */
        private int rateLimitWeightPerMin = 1100;
        private int connectTimeoutMs = 5000;
        private int readTimeoutMs = 10000;
    }

    @Getter
    @Setter
    public static class History {
        private boolean loadOnStartup = false;
        /** Days of daily candles to backfill (3 years). */
        private int daysDaily = 1095;
        /** Days of 15-minute candles to backfill. */
        private int daysIntraday = 90;
        /** Days of 1-hour candles to backfill (a primary TraderBro timeframe). */
        private int daysHourly = 400;
    }

    @Getter
    @Setter
    public static class CoinGecko {
        private boolean enabled = true;
        private String baseUrl = "https://api.coingecko.com/api/v3";
        /** Daily update cron in UTC. */
        private String updateCron = "0 0 8 * * *";
    }
}