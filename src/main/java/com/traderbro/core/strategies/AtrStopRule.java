package com.traderbro.core.strategies;

import org.ta4j.core.Indicator;
import org.ta4j.core.Rule;
import org.ta4j.core.Trade;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;

/**
 * Exit rule that stops a long position when the close falls below
 * {@code entryPrice - atrMult * ATR(current)}. Anchoring on the entry price keeps the stop
 * deterministic and independent of intra-trade highest highs (unlike a pure trailing stop).
 */
public class AtrStopRule implements Rule {

    private final ClosePriceIndicator close;
    private final ATRIndicator atr;
    private final double atrMult;

    public AtrStopRule(ClosePriceIndicator close, ATRIndicator atr, double atrMult) {
        this.close = close;
        this.atr = atr;
        this.atrMult = atrMult;
    }

    @Override
    public boolean isSatisfied(int index, TradingRecord tradingRecord) {
        if (index < 0 || tradingRecord.getCurrentPosition().isNew()) {
            return false;
        }
        Trade entry = tradingRecord.getCurrentPosition().getEntry();
        if (entry == null) {
            return false;
        }
        Num atrValue = atr.getValue(index);
        if (atrValue == null) {
            return false;
        }
        // TODO: verify Trade entry-price accessor name in ta4j 0.24.1
        // (getPricePerAsset() in earlier versions; otherwise use getPrice()).
        Num entryPrice = entry.getPricePerAsset();
        Num stop = entryPrice.minus(atrValue.multipliedBy(close.numFactory().numOf(atrMult)));
        return close.getValue(index).isLessThan(stop);
    }
}