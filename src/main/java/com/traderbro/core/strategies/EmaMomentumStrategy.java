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
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;
import org.ta4j.core.rules.AndRule;
import org.ta4j.core.rules.OverIndicatorRule;
import org.ta4j.core.rules.UnderIndicatorRule;
import org.ta4j.core.rules.OrRule;

/**
 * Momentum strategy: EMA(12)/EMA(26) crossover with an RSI(14) entry filter. Long entries
 * require the fast EMA to cross above the slow EMA while RSI sits within [rsiMin, rsiMax].
 * Exit on the reverse EMA cross <em>or</em> an ATR-based stop.
 * Parameters: {@code fastEm}, {@code slowEm}, {@code rsiPeriod}, {@code rsiMin},
 * {@code rsiMax}, {@code atrPeriod}, {@code atrMult}.
 */
public class EmaMomentumStrategy extends AbstractTradingStrategy {

    public static final String ID = "EmaMomentumStrategy";

    public EmaMomentumStrategy(int moneyScale) {
        super(moneyScale);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Strategy build(BarSeries series, StrategyParams params) {
        int fastEm = params.getInt("fastEm", 12);
        int slowEm = params.getInt("slowEm", 26);
        int rsiPeriod = params.getInt("rsiPeriod", 14);
        double rsiMin = params.getDouble("rsiMin", 30.0);
        double rsiMax = params.getDouble("rsiMax", 70.0);
        int atrPeriod = params.getInt("atrPeriod", 14);
        double atrMult = params.getDouble("atrMult", 2.0);
        if (fastEm >= slowEm) {
            throw new IllegalArgumentException("fastEm must be < slowEm");
        }

        ClosePriceIndicator close = new ClosePriceIndicator(series);
        EMAIndicator fast = new EMAIndicator(close, fastEm);
        EMAIndicator slow = new EMAIndicator(close, slowEm);
        RSIIndicator rsi = new RSIIndicator(close, rsiPeriod);

        Rule emaUp = new CrossedUpIndicatorRule(fast, slow);
        Rule rsiInBand = new AndRule(
                new OverIndicatorRule(rsi, series.numFactory().numOf(rsiMin)),
                new UnderIndicatorRule(rsi, series.numFactory().numOf(rsiMax)));
        Rule entry = new AndRule(emaUp, rsiInBand);

        Rule emaDown = new CrossedDownIndicatorRule(fast, slow);
        ATRIndicator atr = new ATRIndicator(series, atrPeriod);
        Rule atrStop = new AtrStopRule(close, atr, atrMult);
        Rule exit = new OrRule(emaDown, atrStop);

        return new BaseStrategy(entry, exit);
    }

    @Override
    public Optional<Signal> evaluate(BarSeries series, StrategyParams params, SignalFilter filter) {
        int last = series.getEndIndex();
        if (last < 1) {
            return Optional.empty();
        }
        int fastEm = params.getInt("fastEm", 12);
        int slowEm = params.getInt("slowEm", 26);
        int rsiPeriod = params.getInt("rsiPeriod", 14);
        double rsiMin = params.getDouble("rsiMin", 30.0);
        double rsiMax = params.getDouble("rsiMax", 70.0);

        ClosePriceIndicator close = new ClosePriceIndicator(series);
        EMAIndicator fast = new EMAIndicator(close, fastEm);
        EMAIndicator slow = new EMAIndicator(close, slowEm);
        RSIIndicator rsi = new RSIIndicator(close, rsiPeriod);

        if (!SeriesFactory.isPresent(fast, last) || !SeriesFactory.isPresent(slow, last)
                || !SeriesFactory.isPresent(rsi, last)) {
            return Optional.empty();
        }
        Rule entry = new AndRule(
                new CrossedUpIndicatorRule(fast, slow),
                new AndRule(new OverIndicatorRule(rsi, series.numFactory().numOf(rsiMin)),
                        new UnderIndicatorRule(rsi, series.numFactory().numOf(rsiMax))));
        if (!entry.isSatisfied(last)) {
            return Optional.empty();
        }
        Map<String, Object> snap = newSnapshot();
        snap.put("ema_fast", SeriesFactory.last(fast));
        snap.put("ema_slow", SeriesFactory.last(slow));
        snap.put("rsi", SeriesFactory.last(rsi));
        return Optional.of(newEntrySignal(series, filter, snap));
    }
}