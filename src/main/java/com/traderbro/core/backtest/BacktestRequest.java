package com.traderbro.core.backtest;

import com.traderbro.core.domain.enums.CandleInterval;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Builder;

/**
 * A batch backtest request: multiple strategies crossed with multiple instruments, each pair
 * run independently within a single orchestrated run. Costs and optional per-strategy
 * parameter overrides apply to every pair. A record so Jackson 3 can (de)serialize it.
 */
@Builder
public record BacktestRequest(
        List<String> strategyIds,
        List<String> figis,
        CandleInterval interval,
        Instant from,
        Instant to,
        BacktestCosts costs,
        Map<String, Object> strategyParamsOverrides,
        boolean walkForward,
        int walkForwardTrainDays,
        int walkForwardTestDays) {
}