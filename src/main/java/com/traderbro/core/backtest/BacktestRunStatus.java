package com.traderbro.core.backtest;

import lombok.Value;

/** Lifecycle status of an orchestrated batch backtest run. */
@Value
public class BacktestRunStatus {
    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    private BacktestRunStatus() {
    }
}