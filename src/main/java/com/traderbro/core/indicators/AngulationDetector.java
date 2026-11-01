package com.traderbro.core.indicators;

import com.traderbro.api.config.WilliamsProperties;
import com.traderbro.core.domain.AngulationEvent;
import com.traderbro.core.domain.DivergenceEvent;
import com.traderbro.core.domain.enums.CandleInterval;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.num.Num;

/**
 * Formalises Williams' "visceral angle" of price departing from the blue Jaw (Alligator) line.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>Event-driven</b> ({@link #detect}) — measures the angle on the window from the last Jaw
 *       touch up to the extreme bar of an AO divergence. This is the primary signal: angulation
 *       only fires on the extreme peak of a divergence, matching the product owner's spec.</li>
 *   <li><b>Background</b> ({@link #backgroundAngle}) — a standing angle over the last
 *       {@code lookbackBars}, written into {@code indicator_snapshots} for the Dashboard and
 *       generic filters.</li>
 * </ul>
 *
 * <p>Angulation is an <em>amplifier / validity filter</em> for reversal signals, not a standalone
 * entry: per Williams a reversal without angulation is invalid. Slopes are normalised to
 * ATR per bar so the threshold is portable across instruments with different volatility.
 */
public class AngulationDetector {

    /** Bars back to scan for a Jaw touch/cross when deciding "departure vs return to the line". */
    private static final int RECENT_BARS = 3;

    private final int lookbackBars;
    private final double thresholdAtrPerBar;
    private final double thresholdDeg;
    private final int atrPeriod;

    public AngulationDetector(WilliamsProperties.Angulation cfg) {
        this.lookbackBars = cfg.getLookbackBars();
        this.thresholdAtrPerBar = cfg.getThresholdAtrPerBar();
        this.thresholdDeg = cfg.getThresholdDeg();
        this.atrPeriod = cfg.getAtrPeriod();
    }

    public record BackgroundAngle(double angleAtr, double angleDeg,
                                  AngulationEvent.Direction direction) {
    }

    /**
     * Computes the angulation event anchored on the extreme peak of a divergence, if the price
     * actually departed from the Jaw on that window.
     */
    public Optional<AngulationEvent> detect(BarSeries series, AlligatorIndicator alligator,
                                            ATRIndicator atr, DivergenceEvent div,
                                            UUID divergenceEventId) {
        int peak2 = indexOf(series, div.getAoPeak2Ts());
        if (peak2 < 2) {
            return Optional.empty();
        }
        Optional<Integer> anchor = findAnchor(series, alligator, peak2);
        if (anchor.isEmpty()) {
            return Optional.empty();
        }
        int a = anchor.get();
        int barsInWindow = peak2 - a;
        if (barsInWindow < 2) {
            return Optional.empty();
        }
        AngulationEvent.Direction dir = div.getDirection() == DivergenceEvent.Direction.BEARISH
                ? AngulationEvent.Direction.AWAY_UP
                : AngulationEvent.Direction.AWAY_DOWN;

        double atrNorm = atrValue(atr, series, peak2);
        if (!(atrNorm > 0)) {
            return Optional.empty();
        }
        // Bearish (price ran up to the top) → regression on lows; bullish → regression on highs.
        List<Double> priceLine = new ArrayList<>();
        List<Double> jawLine = new ArrayList<>();
        for (int k = a; k <= peak2; k++) {
            priceLine.add(dir == AngulationEvent.Direction.AWAY_UP
                    ? low(series, k) : high(series, k));
            jawLine.add(jawRaw(alligator, series, k));
        }
        double priceSlope = slope(priceLine) / atrNorm;
        double jawSlope = slope(jawLine) / atrNorm;
        double angleAtr = Math.abs(priceSlope - jawSlope);
        double angleDeg = angleDeg(priceSlope, jawSlope);
        // Legacy ATR/bars gate (kept for backward compatibility) + the geometric angle gate
        // (primary, maps to the practitioner's ">45°, closer to 90°").
        if (angleAtr < thresholdAtrPerBar || angleDeg < thresholdDeg) {
            return Optional.empty();
        }
        // Must be a departure (divergence), not a convergence: price farther from Jaw than at anchor.
        double distAnchor = Math.abs(close(series, a) - jawRaw(alligator, series, a));
        double distPeak = Math.abs(close(series, peak2) - jawRaw(alligator, series, peak2));
        if (distPeak <= distAnchor) {
            return Optional.empty();
        }
        // Direction must match: bearish divergence departed upward (price above Jaw at the peak).
        boolean priceAboveJaw = close(series, peak2) > jawRaw(alligator, series, peak2);
        if (dir == AngulationEvent.Direction.AWAY_UP && !priceAboveJaw) {
            return Optional.empty();
        }
        if (dir == AngulationEvent.Direction.AWAY_DOWN && priceAboveJaw) {
            return Optional.empty();
        }
        Instant now = endTime(series, peak2);
        return Optional.of(AngulationEvent.builder()
                .figi(div.getFigi())
                .interval(div.getInterval())
                .ts(now)
                .divergenceEventId(divergenceEventId)
                .direction(dir)
                .priceSlopeAtr(round(priceSlope))
                .jawSlopeAtr(round(jawSlope))
                .angleAtr(round(angleAtr))
                .angleDeg(round(angleDeg))
                .anchorTs(endTime(series, a))
                .barsInWindow(barsInWindow)
                .createdAt(now)
                .build());
    }

    /**
     * Standing background angle over the last {@code lookbackBars}, computed on every closed bar
     * for the Dashboard and generic filters. Uses regression on lows for an above-Jaw move and on
     * highs for a below-Jaw move.
     */
    public Optional<BackgroundAngle> backgroundAngle(BarSeries series, AlligatorIndicator alligator,
                                                     ATRIndicator atr, CandleInterval interval) {
        int n = series.getBarCount();
        return angleAt(series, alligator, atr, n - 1, interval);
    }

    /** Background angle for the window of up to {@code lookbackBars} ending at {@code endIndex}. */
    public Optional<BackgroundAngle> angleAt(BarSeries series, AlligatorIndicator alligator,
                                             ATRIndicator atr, int endIndex,
                                             CandleInterval interval) {
        int a = Math.max(0, endIndex - lookbackBars + 1);
        if (endIndex - a < 2 || endIndex >= series.getBarCount()) {
            return Optional.empty();
        }
        int last = endIndex;
        double atrNorm = atrValue(atr, series, last);
        if (!(atrNorm > 0)) {
            return Optional.empty();
        }
        boolean above = close(series, last) > jawRaw(alligator, series, last);
        List<Double> line = new ArrayList<>();
        for (int k = a; k <= last; k++) {
            line.add(above ? low(series, k) : high(series, k));
        }
        double priceSlope = slope(line) / atrNorm;
        List<Double> jawLine = new ArrayList<>();
        for (int k = a; k <= last; k++) {
            jawLine.add(jawRaw(alligator, series, k));
        }
        double jawSlope = slope(jawLine) / atrNorm;
        double angleAtr = Math.abs(priceSlope - jawSlope);
        double angleDeg = angleDeg(priceSlope, jawSlope);
        // Departure, not a return: if price touched/crossed the Jaw within the last few bars it
        // has come back through the line — that is convergence ("возврат к синей линии"), not
        // angulation.
        if (recentlyCrossedJaw(series, alligator, last)) {
            return Optional.empty();
        }
        return Optional.of(new BackgroundAngle(
                round(angleAtr), round(angleDeg),
                above ? AngulationEvent.Direction.AWAY_UP : AngulationEvent.Direction.AWAY_DOWN));
    }

    /** True when the price touched or crossed the Jaw within the last {@code RECENT_BARS}. */
    private static boolean recentlyCrossedJaw(BarSeries series, AlligatorIndicator alligator,
                                              int last) {
        int recent = Math.min(RECENT_BARS, last);
        for (int k = last; k > last - recent; k--) {
            double jaw = jawRaw(alligator, series, k);
            if (Double.isNaN(jaw)) {
                continue;
            }
            if (jaw >= low(series, k) && jaw <= high(series, k)) {
                return true; // touched the line
            }
            if (k >= 1) {
                double jPrev = jawRaw(alligator, series, k - 1);
                double cCurr = close(series, k);
                double cPrev = close(series, k - 1);
                if (!Double.isNaN(jPrev) && (cPrev - jPrev) * (cCurr - jaw) <= 0) {
                    return true; // crossed through the line
                }
            }
        }
        return false;
    }

    /** Last bar (nearest the extreme) within {@code lookbackBars} where price touched/crossed Jaw. */
    private Optional<Integer> findAnchor(BarSeries series, AlligatorIndicator alligator, int peak2) {
        for (int k = peak2; k >= 0 && peak2 - k <= lookbackBars; k--) {
            double jaw = jawRaw(alligator, series, k);
            if (Double.isNaN(jaw)) {
                continue;
            }
            double lo = low(series, k);
            double hi = high(series, k);
            if (jaw >= lo && jaw <= hi) {
                return Optional.of(k);
            }
            if (k >= 1) {
                double cPrev = close(series, k - 1);
                double jPrev = jawRaw(alligator, series, k - 1);
                double cCurr = close(series, k);
                if (!Double.isNaN(jPrev) && (cPrev - jPrev) * (cCurr - jaw) <= 0) {
                    return Optional.of(k);
                }
            }
        }
        return Optional.empty();
    }

    /** Least-squares slope of {@code ys} over equally spaced bars (units per bar). */
    private static double slope(List<Double> ys) {
        int m = ys.size();
        if (m < 2) {
            return 0.0;
        }
        double sumX = (double) m * (m - 1) / 2.0;
        double sumY = 0.0;
        for (double y : ys) {
            sumY += y;
        }
        double meanX = sumX / m;
        double meanY = sumY / m;
        double num = 0.0;
        double den = 0.0;
        for (int x = 0; x < m; x++) {
            num += (x - meanX) * (ys.get(x) - meanY);
            den += (x - meanX) * (x - meanX);
        }
        return den == 0.0 ? 0.0 : num / den;
    }

    /** Geometric angle (degrees) of the price's departure from the Jaw: the ATR-normalised rate at
     * which price separates from the Jaw, {@code atan(|m1 - m2|)}. 1 ATR/bar separation → 45°,
     * 2 → ~63°, →90° as the departure steepens. If price tracks the Jaw (small difference) the
     * angle is small — i.e. price is following the line, not angulating. */
    private static double angleDeg(double m1, double m2) {
        return Math.toDegrees(Math.atan(Math.abs(m1 - m2)));
    }

    private static int indexOf(BarSeries series, Instant ts) {
        for (int i = 0; i < series.getBarCount(); i++) {
            if (endTime(series, i).equals(ts)) {
                return i;
            }
        }
        return -1;
    }

    private static double jawRaw(AlligatorIndicator alligator, BarSeries series, int i) {
        if (i < 0 || i >= series.getBarCount()) {
            return Double.NaN;
        }
        Num v = alligator.jawRaw(i);
        return v == null ? Double.NaN : v.doubleValue();
    }

    private static double atrValue(ATRIndicator atr, BarSeries series, int i) {
        if (i < 0 || i >= series.getBarCount()) {
            return Double.NaN;
        }
        Num v = atr.getValue(i);
        return v == null ? Double.NaN : v.doubleValue();
    }

    private static double close(BarSeries series, int i) {
        Bar b = series.getBar(i);
        return b == null ? Double.NaN : b.getClosePrice().doubleValue();
    }

    private static double high(BarSeries series, int i) {
        Bar b = series.getBar(i);
        return b == null ? Double.NaN : b.getHighPrice().doubleValue();
    }

    private static double low(BarSeries series, int i) {
        Bar b = series.getBar(i);
        return b == null ? Double.NaN : b.getLowPrice().doubleValue();
    }

    private static Instant endTime(BarSeries series, int i) {
        Bar b = series.getBar(i);
        return b == null ? Instant.EPOCH : b.getEndTime();
    }

    private static double round(double v) {
        return Double.isFinite(v) ? Math.round(v * 1000.0) / 1000.0 : 0.0;
    }
}