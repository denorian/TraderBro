package com.traderbro.core.backtest;

import java.time.Instant;
import lombok.Builder;

/**
 * A single signal point produced during a backtest, used to pin ENTRY/EXIT markers on the
 * chart. Carries the verdict so the UI can render rejected/filtered signals separately.
 */
@Builder
public record SignalRecord(
        String strategyId,
        String figi,
        Instant ts,
        SignalType type,
        double price,
        String verdict,
        String reason) {

    /** Whether the point opens (ENTRY) or closes (EXIT) a position. */
    public enum SignalType {
        ENTRY,
        EXIT
    }
}