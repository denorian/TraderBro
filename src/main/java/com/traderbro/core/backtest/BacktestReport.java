package com.traderbro.core.backtest;

import com.traderbro.core.domain.enums.CandleInterval;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import lombok.Builder;
import lombok.Value;

/**
 * Result of a backtest run, persisted to {@code backtest_runs} and exported to Markdown.
 */
@Value
@Builder
public class BacktestReport {

    String id;
    String strategyId;
    String figi;
    CandleInterval interval;
    Instant from;
    Instant to;
    Map<String, String> params;
    BacktestMetrics metrics;
    /** git-hash / build version the run was produced with. */
    String codeVersion;
    Instant createdAt;
    long barsCount;
}