package com.traderbro.core.backtest;

import lombok.Value;

/** A single closed simulated trade, used to derive win-rate, duration and profit factor. */
@Value
public class BacktestTrade {

    long entryBar;
    double entryEquity;
    double exitEquity;
    long barsHeld;

    public boolean isWin() {
        return exitEquity > entryEquity;
    }
}