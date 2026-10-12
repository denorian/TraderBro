package com.traderbro.core.indicators;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.HighestValueIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.indicators.helpers.LowestValueIndicator;
import org.ta4j.core.num.Num;

/**
 * Simplest look-ahead-free "fractal" for stop placement: the trailing extremum of the last
 * {@code lookback} bars. {@link Type#HIGH} returns the highest high and {@link Type#LOW} the
 * lowest low over the window ending at {@code index} (inclusive).
 *
 * <p><b>Design choice:</b> a true Williams fractal needs confirmation bars <em>after</em> the
 * extremum, which would introduce look-ahead into live stops. Instead this uses the trailing
 * extremum over {@code stopLookback} bars (Williams {@code stopLookback=5} by default), which
 * is a strict super-set of "the nearest confirmed fractal" and is computable on bar {@code i}
 * using only data {@code <= i}.
 */
public class FractalIndicator extends CachedIndicator<Num> {

    /** Whether the fractal tracks the trailing maximum (HIGH) or minimum (LOW). */
    public enum Type { HIGH, LOW }

    private final Indicator<Num> extreme;
    private final Type type;
    private final int lookback;

    public FractalIndicator(BarSeries series, Type type, int lookback) {
        super(series);
        if (lookback < 1) {
            throw new IllegalArgumentException("lookback must be >= 1: " + lookback);
        }
        this.type = type;
        this.lookback = lookback;
        this.extreme = type == Type.HIGH
                ? new HighestValueIndicator(new HighPriceIndicator(series), lookback)
                : new LowestValueIndicator(new LowPriceIndicator(series), lookback);
    }

    @Override
    protected Num calculate(int index) {
        return extreme.getValue(index);
    }

    public Type getType() {
        return type;
    }

    public int getLookback() {
        return lookback;
    }

    @Override
    public int getCountOfUnstableBars() {
        return lookback;
    }

    @Override
    public String toString() {
        return "FractalIndicator(" + type + "," + lookback + ")";
    }
}