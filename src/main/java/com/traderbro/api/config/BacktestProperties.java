package com.traderbro.api.config;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Backtest orchestration and defaults ({@code backtest.*}).
 *
 * <p>The executor pool is intentionally small and isolated so batch backtests never compete
 * with the live trading loop for CPU. Results are retained for a bounded window and purged
 * by a scheduled job ({@code results-retention-days}).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "backtest")
public class BacktestProperties {

    /** Dedicated executor pool size for backtest runs. */
    private int executorPoolSize = 2;
    /** Default per-side commission, percent (UI overrides via the request). */
    private BigDecimal defaultCommissionPct = new BigDecimal("0.05");
    /** Default slippage, basis points (UI overrides via the request). */
    private int defaultSlippageBps = 5;
    /** Safety cap on signals persisted per run (guards runaway strategies). */
    private long maxSignalsPerRun = 100_000;
    /** How long completed backtest results are kept before the purge job removes them. */
    private int resultsRetentionDays = 180;
    /** Walk-forward window lengths (days). */
    private WalkForward walkForward = new WalkForward();

    @Getter
    @Setter
    public static class WalkForward {
        private int trainDays = 180;
        private int testDays = 60;
    }
}