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
import org.ta4j.core.indicators.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;
import org.ta4j.core.rules.OrRule;
import org.ta4j.core.rules.StopLossRule;

/**
 * Mean-reversion strategy: long entry when the close touches/crosses below the lower
 * Bollinger band (period, k-sigma); exit when price returns to the middle band or the fixed
 * stop-loss percentage is hit. Parameters: {@code period}, {@code k}, {@code stopLossPct}.
 */
public class BollingerMeanReversionStrategy extends AbstractTradingStrategy {

    public static final String ID = "BollingerMeanReversionStrategy";

    public BollingerMeanReversionStrategy(int moneyScale) {
        super(moneyScale);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Strategy build(BarSeries series, StrategyParams params) {
        int period = params.getInt("period", 20);
        double k = params.getDouble("k", 2.0);
        double stopLossPct = params.getDouble("stopLossPct", 0.05);

        ClosePriceIndicator close = new ClosePriceIndicator(series);
        BollingerBandsMiddleIndicator middle = new BollingerBandsMiddleIndicator(
                new org.ta4j.core.indicators.SMAIndicator(close, period));
        BollingerBandsLowerIndicator lower =
                new BollingerBandsLowerIndicator(middle, close, period, series.numFactory().numOf(k));

        Rule entry = new CrossedDownIndicatorRule(close, lower);
        Rule revert = new CrossedUpIndicatorRule(close, middle);
        // TODO: verify StopLossRule percentage semantics in ta4j 0.24.1 (loss from highest high).
        Rule stop = new StopLossRule(close, stopLossPct);
        Rule exit = new OrRule(revert, stop);
        return new BaseStrategy(entry, exit);
    }

    @Override
    public Optional<Signal> evaluate(BarSeries series, StrategyParams params, SignalFilter filter) {
        int last = series.getEndIndex();
        if (last < 1) {
            return Optional.empty();
        }
        int period = params.getInt("period", 20);
        double k = params.getDouble("k", 2.0);

        ClosePriceIndicator close = new ClosePriceIndicator(series);
        BollingerBandsMiddleIndicator middle = new BollingerBandsMiddleIndicator(
                new org.ta4j.core.indicators.SMAIndicator(close, period));
        BollingerBandsLowerIndicator lower =
                new BollingerBandsLowerIndicator(middle, close, period, series.numFactory().numOf(k));

        if (!SeriesFactory.isPresent(close, last) || !SeriesFactory.isPresent(lower, last)) {
            return Optional.empty();
        }
        Rule entry = new CrossedDownIndicatorRule(close, lower);
        if (!entry.isSatisfied(last)) {
            return Optional.empty();
        }
        Map<String, Object> snap = newSnapshot();
        snap.put("bollinger_middle", SeriesFactory.last(middle));
        snap.put("bollinger_lower", SeriesFactory.last(lower));
        return Optional.of(newEntrySignal(series, filter, snap));
    }
}