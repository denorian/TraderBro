package com.traderbro.core.strategies;

import com.traderbro.core.domain.Signal;
import com.traderbro.core.domain.spi.SignalFilter;
import com.traderbro.core.indicators.SeriesFactory;
import java.util.Map;
import java.util.Optional;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Strategy;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Rule;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;

/**
 * Long-only trend strategy: entry on SMA(fast) crossing above SMA(slow),
 * exit on SMA(fast) crossing below SMA(slow). Parameters {@code fast}, {@code slow}.
 */
public class SmaCrossStrategy extends AbstractTradingStrategy {

    public static final String ID = "SmaCrossStrategy";
    public static final String DEFAULT_FAST = "20";
    public static final String DEFAULT_SLOW = "100";

    public SmaCrossStrategy(int moneyScale) {
        super(moneyScale);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Strategy build(BarSeries series, StrategyParams params) {
        int fast = params.getInt("fast", 20);
        int slow = params.getInt("slow", 100);
        if (fast >= slow) {
            throw new IllegalArgumentException("fast must be < slow: fast=" + fast + ", slow=" + slow);
        }
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        SMAIndicator fastSma = new SMAIndicator(close, fast);
        SMAIndicator slowSma = new SMAIndicator(close, slow);
        Rule entry = new CrossedUpIndicatorRule(fastSma, slowSma);
        Rule exit = new CrossedDownIndicatorRule(fastSma, slowSma);
        return new BaseStrategy(entry, exit);
    }

    @Override
    public Optional<Signal> evaluate(BarSeries series, StrategyParams params, SignalFilter filter) {
        int last = series.getEndIndex();
        if (last < 1) {
            return Optional.empty();
        }
        int fast = params.getInt("fast", 20);
        int slow = params.getInt("slow", 100);
        if (fast >= slow) {
            throw new IllegalArgumentException("fast must be < slow: fast=" + fast + ", slow=" + slow);
        }
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        SMAIndicator fastSma = new SMAIndicator(close, fast);
        SMAIndicator slowSma = new SMAIndicator(close, slow);
        if (!SeriesFactory.isPresent(fastSma, last) || !SeriesFactory.isPresent(slowSma, last)) {
            return Optional.empty();
        }
        Rule entry = new CrossedUpIndicatorRule(fastSma, slowSma);
        if (!entry.isSatisfied(last)) {
            return Optional.empty();
        }
        Map<String, Object> snap = newSnapshot();
        snap.put("sma_fast", SeriesFactory.last(fastSma));
        snap.put("sma_slow", SeriesFactory.last(slowSma));
        return Optional.of(newEntrySignal(series, filter, snap));
    }
}