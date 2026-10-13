package com.traderbro.core.backtest;

import java.util.List;
import lombok.Builder;

/**
 * Detailed simulation outcome: metrics plus per-bar equity, closed trades and ENTRY/EXIT
 * signal points. Backing data for the UI result card and chart pins.
 */
@Builder
public record SimulationResult(
        BacktestMetrics metrics,
        List<TradeRecord> trades,
        List<SignalRecord> signals,
        List<EquityPoint> equity) {
}