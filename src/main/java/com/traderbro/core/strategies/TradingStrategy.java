package com.traderbro.core.strategies;

import com.traderbro.core.domain.Signal;
import com.traderbro.core.domain.spi.SignalFilter;
import java.util.Optional;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Strategy;

/**
 * A pluggable trading strategy over a ta4j {@link BarSeries}.
 * <p>Live signal generation calls {@link #evaluate}; backtesting uses {@link #build}.
 * Strategies are long-only in stage 1.
 */
public interface TradingStrategy {

    /** Stable strategy id matching a row in {@code strategy_config}. */
    String id();

    /**
     * Builds the ta4j {@link Strategy} (entry/exit rules) used by the backtester.
     * The series must already be populated; rules reference indicators over that series.
     */
    Strategy build(BarSeries series, StrategyParams params);

    /**
     * Evaluates the series' last <em>closed</em> bar for a new long entry.
     *
     * @return a LONG entry {@link Signal} when an entry triggered on the latest bar, else empty.
     *         The signal verdict is assigned by the caller after consulting risk/filters; here it
     *         is computed with the filter weight only.
     */
    Optional<Signal> evaluate(BarSeries series, StrategyParams params, SignalFilter filter);

    /** Human-readable strategy name for reports and logs. */
    default String displayName() {
        return getClass().getSimpleName();
    }
}