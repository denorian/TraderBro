package com.traderbro.core.strategies;

import com.traderbro.core.domain.Signal;
import com.traderbro.core.domain.spi.SignalFilter;
import com.traderbro.core.indicators.SeriesFactory;
import java.util.Map;
import java.util.Optional;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Rule;
import org.ta4j.core.Strategy;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.indicators.HighestValueIndicator;
import org.ta4j.core.indicators.LowestValueIndicator;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;

/**
 * Trend-following breakout: long entry when close breaks above the upper Donchian channel of
 * the last {@code n} bars; exit when close breaks below the lower channel of the last
 * {@code m} bars. Parameters: {@code n}, {@code m}.
 */
public class DonchianBreakoutStrategy extends AbstractTradingStrategy {

    public static final String ID = "DonchianBreakoutStrategy";

    public DonchianBreakoutStrategy(int moneyScale) {
        super(moneyScale);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Strategy build(BarSeries series, StrategyParams params) {
        int n = params.getInt("n", 20);
        int m = params.getInt("m", 10);
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        HighestValueIndicator upper = new HighestValueIndicator(new HighPriceIndicator(series), n);
        LowestValueIndicator lower = new LowestValueIndicator(new LowPriceIndicator(series), m);
        Rule entry = new CrossedUpIndicatorRule(close, upper);
        Rule exit = new CrossedDownIndicatorRule(close, lower);
        return new BaseStrategy(entry, exit);
    }

    @Override
    public Optional<Signal> evaluate(BarSeries series, StrategyParams params, SignalFilter filter) {
        int last = series.getEndIndex();
        if (last < 1) {
            return Optional.empty();
        }
        int n = params.getInt("n", 20);
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        HighestValueIndicator upper = new HighestValueIndicator(new HighPriceIndicator(series), n);
        if (!SeriesFactory.isPresent(close, last) || !SeriesFactory.isPresent(upper, last)) {
            return Optional.empty();
        }
        Rule entry = new CrossedUpIndicatorRule(close, upper);
        if (!entry.isSatisfied(last)) {
            return Optional.empty();
        }
        Map<String, Object> snap = newSnapshot();
        snap.put("donchian_upper", SeriesFactory.last(upper));
        return Optional.of(newEntrySignal(series, filter, snap));
    }
}