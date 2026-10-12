package com.traderbro.core.indicators;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.AbstractIndicator;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.num.Num;

/**
 * Bill Williams Alligator: three smoothed median-price lines shifted forward in time.
 *
 * <pre>
 *   Jaw   = SMA(13) shifted +8 bars
 *   Teeth = SMA( 8) shifted +5 bars
 *   Lips  = SMA( 5) shifted +3 bars
 * </pre>
 *
 * <h2>No look-ahead</h2>
 * The "shift forward" is a <em>plotting</em> convention: the value of a line on bar {@code i}
 * is the SMA computed on bar {@code i - shift} (i.e. it lags behind, never uses future data).
 * {@link #jaw(int)}, {@link #teeth(int)}, {@link #lips(int)} expose exactly those shifted
 * values for analytics and the UI. For <strong>live signals</strong> use the unshifted current
 * values {@link #jawRaw(int)} / {@link #teethRaw(int)} / {@link #lipsRaw(int)} so the decision
 * on bar {@code i} only ever uses data {@code <= i}. {@link #state(int)} and {@link #fanWidth(int)}
 * are computed from those current values.
 *
 * <p>The {@link Indicator} contract is satisfied by returning the shifted Lips value from
 * {@link #getValue(int)} so the composite can be used as a single ta4j indicator; the three
 * lines are the real output.
 */
public class AlligatorIndicator extends AbstractIndicator<Num> {

    /** Phase classifier of the Alligator fan. */
    public enum AlligatorState {
        /** Lines intertwined — distance below the ATR threshold. */
        SLEEPING,
        /** Lines separating but the fan is not fully ordered yet. */
        AWAKENING,
        /** Ordered bullish fan: lips &gt; teeth &gt; jaw. */
        EATING_BULL,
        /** Ordered bearish fan: lips &lt; teeth &lt; jaw. */
        EATING_BEAR
    }

    private final MedianPriceIndicator median;
    private final SMAIndicator jawSma;
    private final SMAIndicator teethSma;
    private final SMAIndicator lipsSma;
    private final ATRIndicator atr;
    private final int jawPeriod;
    private final int jawShift;
    private final int teethShift;
    private final int lipsShift;
    private final double entanglementAtrThreshold;

    public AlligatorIndicator(BarSeries series, int jawPeriod, int jawShift,
                              int teethPeriod, int teethShift,
                              int lipsPeriod, int lipsShift,
                              int atrPeriod, double entanglementAtrThreshold) {
        super(series);
        if (jawShift < 0 || teethShift < 0 || lipsShift < 0) {
            throw new IllegalArgumentException("Alligator shifts must be >= 0");
        }
        this.median = new MedianPriceIndicator(series);
        this.jawSma = new SMAIndicator(median, jawPeriod);
        this.teethSma = new SMAIndicator(median, teethPeriod);
        this.lipsSma = new SMAIndicator(median, lipsPeriod);
        this.atr = new ATRIndicator(series, atrPeriod);
        this.jawPeriod = jawPeriod;
        this.jawShift = jawShift;
        this.teethShift = teethShift;
        this.lipsShift = lipsShift;
        this.entanglementAtrThreshold = entanglementAtrThreshold;
    }

    public int getJawPeriod() {
        return jawPeriod;
    }

    /** Shifted Jaw value for plotting/analytics; {@code null} before {@code jawShift} bars. */
    public Num jaw(int index) {
        return shifted(jawSma, jawShift, index);
    }

    /** Shifted Teeth value for plotting/analytics; {@code null} before {@code teethShift} bars. */
    public Num teeth(int index) {
        return shifted(teethSma, teethShift, index);
    }

    /** Shifted Lips value for plotting/analytics; {@code null} before {@code lipsShift} bars. */
    public Num lips(int index) {
        return shifted(lipsSma, lipsShift, index);
    }

    /** Current (unshifted) Jaw — the live decision value at {@code index}. */
    public Num jawRaw(int index) {
        return getBarSeries().getBarCount() > index ? jawSma.getValue(index) : null;
    }

    /** Current (unshifted) Teeth — the live decision value at {@code index}. */
    public Num teethRaw(int index) {
        return getBarSeries().getBarCount() > index ? teethSma.getValue(index) : null;
    }

    /** Current (unshifted) Lips — the live decision value at {@code index}. */
    public Num lipsRaw(int index) {
        return getBarSeries().getBarCount() > index ? lipsSma.getValue(index) : null;
    }

    /** @return the shifted Lips value, satisfying the {@link Indicator} contract. */
    @Override
    public Num getValue(int index) {
        return lips(index);
    }

    /**
     * Classifies the Alligator phase at {@code index} using only data {@code <= index}.
     * Lines are considered entangled (SLEEPING) when the spread between the three current
     * lines is below {@code entanglementAtrThreshold * ATR}. Otherwise a strictly ordered
     * fan yields EATING_BULL / EATING_BEAR and any other separated configuration AWAKENING.
     */
    public AlligatorState state(int index) {
        Num j = jawRaw(index);
        Num t = teethRaw(index);
        Num l = lipsRaw(index);
        Num a = atr.getValue(index);
        if (j == null || t == null || l == null || a == null || a.isZero()
                || a.isLessThan(getBarSeries().numFactory().zero())) {
            return AlligatorState.SLEEPING;
        }
        Num spread = max(l, t, j).minus(min(l, t, j));
        Num threshold = a.multipliedBy(getBarSeries().numFactory().numOf(entanglementAtrThreshold));
        if (spread.isLessThan(threshold)) {
            return AlligatorState.SLEEPING;
        }
        if (l.isGreaterThan(t) && t.isGreaterThan(j)) {
            return AlligatorState.EATING_BULL;
        }
        if (l.isLessThan(t) && t.isLessThan(j)) {
            return AlligatorState.EATING_BEAR;
        }
        return AlligatorState.AWAKENING;
    }

    /** Fan width in fractions of ATR at {@code index} (0 when not computable). */
    public double fanWidth(int index) {
        Num j = jawRaw(index);
        Num t = teethRaw(index);
        Num l = lipsRaw(index);
        Num a = atr.getValue(index);
        if (j == null || t == null || l == null || a == null || a.isZero()) {
            return 0.0;
        }
        Num spread = max(l, t, j).minus(min(l, t, j));
        return spread.dividedBy(a).doubleValue();
    }

    /** Whether all three current lines are present at {@code index} (enough history). */
    public boolean isReady(int index) {
        return index >= 0 && index < getBarSeries().getBarCount()
                && jawRaw(index) != null && teethRaw(index) != null && lipsRaw(index) != null
                && atr.getValue(index) != null;
    }

    public ATRIndicator getAtr() {
        return atr;
    }

    /** Number of warm-up bars before the slowest line (Jaw) becomes meaningful. */
    @Override
    public int getCountOfUnstableBars() {
        return getJawPeriod();
    }

    public int getJawShift() {
        return jawShift;
    }

    public int getTeethShift() {
        return teethShift;
    }

    public int getLipsShift() {
        return lipsShift;
    }

    private Num shifted(SMAIndicator sma, int shift, int index) {
        int src = index - shift;
        if (src < 0 || src >= getBarSeries().getBarCount()) {
            return null;
        }
        return sma.getValue(src);
    }

    private static Num max(Num a, Num b, Num c) {
        Num m = a.isGreaterThan(b) ? a : b;
        return m.isGreaterThan(c) ? m : c;
    }

    private static Num min(Num a, Num b, Num c) {
        Num m = a.isLessThan(b) ? a : b;
        return m.isLessThan(c) ? m : c;
    }

    @Override
    public String toString() {
        return "AlligatorIndicator(jaw=13/8, teeth=8/5, lips=5/3)";
    }
}