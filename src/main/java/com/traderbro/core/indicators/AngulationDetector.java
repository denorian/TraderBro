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

    private final int lookbackBars;
    private final double thresholdAtrPerBar;
    private final int atrPeriod;

    public AngulationDetector(WilliamsProperties.Angulation cfg) {
        this.lookbackBars = cfg.getLookbackBars();
        this.thresholdAtrPerBar = cfg.getThresholdAtrPerBar();
        this.atrPeriod = cfg.getAtrPeriod();
    }

    public record BackgroundAngle(double angleAtr, AngulationEvent.Direction direction) {
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
        if (angleAtr < thresholdAtrPerBar) {
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
        return Optional.of(new BackgroundAngle(
                round(angleAtr),
                above ? AngulationEvent.Direction.AWAY_UP : AngulationEvent.Direction.AWAY_DOWN));
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