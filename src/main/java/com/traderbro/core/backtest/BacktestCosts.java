package com.traderbro.core.backtest;

import java.math.BigDecimal;
import lombok.Builder;

/**
 * Cost model for a backtest run. Percent commission is applied per side for shares;
 * futures use per-contract fees plus slippage in ticks derived from these values.
 * A record so Jackson 3 can (de)serialize it via the canonical constructor.
 */
@Builder
public record BacktestCosts(
        BigDecimal commissionPct,
        int slippageBps,
        BigDecimal initialCapital,
        BigDecimal maxPositionPct) {

    public static BacktestCosts defaults() {
        return new BacktestCosts(new BigDecimal("0.0005"), 5,
                new BigDecimal("1000000"), new BigDecimal("0.95"));
    }
}