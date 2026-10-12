package com.traderbro.core.indicators;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.num.Num;

/**
 * Bill Williams median price: {@code (High + Low) / 2}. The building block for both the
 * Awesome Oscillator and the Alligator, which Williams derives from the median price rather
 * than the close. Same formula as ta4j's built-in {@code MedianPriceIndicator}; provided here
 * so the whole Williams family lives in this package.
 */
public class MedianPriceIndicator extends CachedIndicator<Num> {

    private final HighPriceIndicator high;
    private final LowPriceIndicator low;

    public MedianPriceIndicator(BarSeries series) {
        super(series);
        this.high = new HighPriceIndicator(series);
        this.low = new LowPriceIndicator(series);
    }

    @Override
    protected Num calculate(int index) {
        Num h = high.getValue(index);
        Num l = low.getValue(index);
        return h.plus(l).dividedBy(getBarSeries().numFactory().numOf(2));
    }

    @Override
    public int getCountOfUnstableBars() {
        return 0;
    }

    @Override
    public String toString() {
        return "MedianPriceIndicator()";
    }
}