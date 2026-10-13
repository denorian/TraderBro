package com.traderbro.core.backtest;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * Result of one strategy × instrument pair within a batch run: metrics plus the detailed
 * trades, signals and equity curve used by the UI.
 */
@Value
@Builder
public class PairResult {

    String strategyId;
    String figi;
    BacktestMetrics metrics;
    long barsCount;
    List<TradeRecord> trades;
    List<SignalRecord> signals;
    List<EquityPoint> equity;
}