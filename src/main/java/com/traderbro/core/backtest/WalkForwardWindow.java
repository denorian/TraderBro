package com.traderbro.core.backtest;

import java.time.Instant;
import java.util.Map;
import lombok.Value;

/** One train/test window of a walk-forward run. */
@Value
public class WalkForwardWindow {

    Instant trainFrom;
    Instant trainTo;
    Instant testFrom;
    Instant testTo;
    Map<String, String> chosenParams;
    BacktestMetrics trainMetrics;
    BacktestMetrics testMetrics;
}