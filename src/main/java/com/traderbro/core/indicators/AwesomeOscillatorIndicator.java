package com.traderbro.core.indicators;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.num.Num;

/**
 * Bill Williams Awesome Oscillator: {@code AO = SMA(fast, median) - SMA(slow, median)} with
 * the classic Williams windows {@code (5, 34)}. Positive AO implies short-term momentum is
 * above the longer baseline; the zero crossing is the primary entry trigger.
 * <p>Derived per-bar signals used by the strategy and the UI:
 * <ul>
 *   <li>{@link #isRising(int)} — current AO above the previous bar (histogram bar colour);</li>
 *   <li>{@link #isBullishSaucer(int)} / {@link #isBearishSaucer(int)} — three-bar "saucer"
 *       where the middle bar is the extremum and all bars share the sign (Williams trigger);</li>
 *   <li>{@link #isAboveZero(int)} / {@link #isBelowZero(int)}.</li>
 * </ul>
 * All methods are look-ahead-free: they use only values at indices {@code <= index}.
 */
public class AwesomeOscillatorIndicator extends CachedIndicator<Num> {

    private final SMAIndicator fastSma;
    private final SMAIndicator slowSma;
    private final int fastPeriod;
    private final int slowPeriod;

    public AwesomeOscillatorIndicator(BarSeries series, int fastPeriod, int slowPeriod) {
        super(series);
        if (fastPeriod <= 0 || slowPeriod <= 0) {
            throw new IllegalArgumentException("AO periods must be > 0: " + fastPeriod + ", " + slowPeriod);
        }
        MedianPriceIndicator median = new MedianPriceIndicator(series);
        this.fastSma = new SMAIndicator(median, fastPeriod);
        this.slowSma = new SMAIndicator(median, slowPeriod);
        this.fastPeriod = fastPeriod;
        this.slowPeriod = slowPeriod;
    }

    public AwesomeOscillatorIndicator(BarSeries series) {
        this(series, 5, 34);
    }

    @Override
    protected Num calculate(int index) {
        return fastSma.getValue(index).minus(slowSma.getValue(index));
    }

    /** True when the AO value rose versus the previous closed bar. */
    public boolean isRising(int index) {
        if (index < 1 || !isPresent(index) || !isPresent(index - 1)) {
            return false;
        }
        return getValue(index).isGreaterThan(getValue(index - 1));
    }

    /**
     * Bullish saucer: three consecutive bars, the middle lower than both neighbours and all
     * three above zero. Look-ahead-free (uses {@code index-2..index}).
     */
    public boolean isBullishSaucer(int index) {
        if (index < 2) {
            return false;
        }
        Num a = getValue(index - 2);
        Num b = getValue(index - 1);
        Num c = getValue(index);
        if (a == null || b == null || c == null) {
            return false;
        }
        Num zero = getBarSeries().numFactory().zero();
        return b.isLessThan(a) && b.isLessThan(c)
                && a.isGreaterThan(zero) && b.isGreaterThan(zero) && c.isGreaterThan(zero);
    }

    /** Mirror of {@link #isBullishSaucer(int)} below the zero line. */
    public boolean isBearishSaucer(int index) {
        if (index < 2) {
            return false;
        }
        Num a = getValue(index - 2);
        Num b = getValue(index - 1);
        Num c = getValue(index);
        if (a == null || b == null || c == null) {
            return false;
        }
        Num zero = getBarSeries().numFactory().zero();
        return b.isGreaterThan(a) && b.isGreaterThan(c)
                && a.isLessThan(zero) && b.isLessThan(zero) && c.isLessThan(zero);
    }

    public boolean isAboveZero(int index) {
        return isPresent(index) && getValue(index).isGreaterThan(getBarSeries().numFactory().zero());
    }

    public boolean isBelowZero(int index) {
        return isPresent(index) && getValue(index).isLessThan(getBarSeries().numFactory().zero());
    }

    public int getFastPeriod() {
        return fastPeriod;
    }

    public int getSlowPeriod() {
        return slowPeriod;
    }

    private boolean isPresent(int index) {
        return index >= 0 && index < getBarSeries().getBarCount();
    }

    @Override
    public int getCountOfUnstableBars() {
        return slowPeriod;
    }

    @Override
    public String toString() {
        return "AwesomeOscillatorIndicator(" + fastPeriod + "," + slowPeriod + ")";
    }
}