package com.traderbro.core.backtest;

import java.util.List;
import lombok.Value;

/** Aggregated result of a walk-forward run across all sliding windows. */
@Value
public class WalkForwardResult {

    List<WalkForwardWindow> windows;
    double avgTestSharpe;
    double avgTestTotalReturn;
    double avgTestMaxDrawdown;
    long totalTrades;

    public boolean isEmpty() {
        return windows.isEmpty();
    }
}