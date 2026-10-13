package com.traderbro.core.backtest;

import java.time.Instant;
import lombok.Builder;

/** A single equity-curve point of a backtest pair. */
@Builder
public record EquityPoint(Instant ts, double equity) {
}