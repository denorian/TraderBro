package com.traderbro.core.backtest;

import com.traderbro.core.domain.enums.Direction;
import java.time.Instant;
import lombok.Builder;

/**
 * A single simulated round-trip trade, captured with full detail for UI display and pinning
 * on the price chart. P&L is expressed both in currency units and percent of entry notional.
 */
@Builder
public record TradeRecord(
        String strategyId,
        String figi,
        Direction direction,
        Instant entryTs,
        double entryPrice,
        Instant exitTs,
        double exitPrice,
        double quantity,
        double pnl,
        double pnlPct,
        String exitReason) {
}