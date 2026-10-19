package com.traderbro.core.indicators;

import com.traderbro.api.config.WilliamsProperties;
import com.traderbro.core.domain.DivergenceEvent;
import com.traderbro.core.domain.enums.CandleInterval;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.num.Num;

/**
 * Bill Williams "Twin Peaks" divergence detector on the Awesome Oscillator histogram. A divergence
 * is a <em>formation on the AO histogram itself</em>: two consecutive AO extrema on the same side
 * of zero, the second weaker in absolute value while price updates its extremum.
 *
 * <p>All decisions are look-ahead-free: an AO extremum at bar {@code p} is only confirmed once
 * {@code aoPeakWindow} bars to the right have closed lower (peaks) / higher (troughs), so a
 * formation can never fire before its confirmation bar.
 *
 * <p>The detector is a pure function over a {@link BarSeries} — it never touches the DB or the
 * event bus; {@code WilliamsEventDetector} persists and notifies.
 */
public class AoTwinPeaksDetector {

    private final int aoPeakWindow;
    private final int minPeakDistance;
    private final int maxPeakDistance;
    private final double minPeakAbsAtr;
    private final int confirmBars;
    private final int atrPeriod;

    public AoTwinPeaksDetector(WilliamsProperties.Divergence cfg) {
        this.aoPeakWindow = cfg.getAoPeakWindow();
        this.minPeakDistance = cfg.getMinPeakDistance();
        this.maxPeakDistance = cfg.getMaxPeakDistance();
        this.minPeakAbsAtr = cfg.getMinPeakAbsAtr();
        this.confirmBars = cfg.getConfirmBars();
        this.atrPeriod = cfg.getAtrPeriod();
    }

    /** Scans the series and returns every completed Twin-Peaks formation (both directions). */
    public List<DivergenceEvent> scan(BarSeries series, AwesomeOscillatorIndicator ao,
                                      CandleInterval interval, String figi) {
        ATRIndicator atr = new ATRIndicator(series, atrPeriod);
        List<DivergenceEvent> out = new ArrayList<>();
        out.addAll(scanPolarity(series, ao, atr, interval, figi, Polarity.PEAKS_ABOVE_ZERO));
        out.addAll(scanPolarity(series, ao, atr, interval, figi, Polarity.TROUGHS_BELOW_ZERO));
        return out;
    }

    private enum Polarity {
        /** Bearish: two falling peaks above zero with a new price high. */
        PEAKS_ABOVE_ZERO,
        /** Bullish: two rising troughs below zero with a new price low. */
        TROUGHS_BELOW_ZERO
    }

    private List<DivergenceEvent> scanPolarity(BarSeries series, AwesomeOscillatorIndicator ao,
                                               ATRIndicator atr, CandleInterval interval,
                                               String figi, Polarity p) {
        List<DivergenceEvent> out = new ArrayList<>();
        List<Extremum> run = new ArrayList<>();
        int n = series.getBarCount();
        for (int i = 0; i < n; i++) {
            double aoi = ao(i, ao, series);
            if (Double.isNaN(aoi)) {
                continue;
            }
            boolean inSign = p == Polarity.PEAKS_ABOVE_ZERO ? aoi > 0 : aoi < 0;
            if (!inSign) {
                // Zero cross: the current same-side run is over; start fresh.
                run.clear();
                continue;
            }
            int cand = i - aoPeakWindow;
            if (cand < 0) {
                continue;
            }
            // Confirm an extremum at `cand` using only bars up to `i`.
            double aoCand = ao(cand, ao, series);
            if (Double.isNaN(aoCand) || !isExtremum(aoCand, p)) {
                continue;
            }
            if (!confirmExtremum(series, ao, cand, i, p)) {
                continue;
            }
            run.add(new Extremum(cand, aoCand));
            maybeEmit(series, ao, atr, interval, figi, run, p, i, out);
        }
        return out;
    }

    private void maybeEmit(BarSeries series, AwesomeOscillatorIndicator ao, ATRIndicator atr,
                           CandleInterval interval, String figi, List<Extremum> run,
                           Polarity p, int now, List<DivergenceEvent> out) {
        if (run.size() < 2) {
            return;
        }
        Extremum prev = run.get(run.size() - 2);
        Extremum curr = run.get(run.size() - 1);
        int dist = curr.index - prev.index;
        if (dist < minPeakDistance || dist > maxPeakDistance) {
            return;
        }
        // Exhaustion: the second extremum is weaker in absolute value.
        if (p == Polarity.PEAKS_ABOVE_ZERO ? curr.value >= prev.value : curr.value <= prev.value) {
            return;
        }
        // Price confirms: new high (bearish) / new low (bullish) on the second AO extremum.
        double highPrev = high(series, prev.index);
        double highCurr = high(series, curr.index);
        if (p == Polarity.PEAKS_ABOVE_ZERO ? highCurr <= highPrev : highCurr >= highPrev) {
            return;
        }
        // Amplitude of the first extremum must be meaningful relative to ATR (filter noise at 0).
        double atrPrev = atrValue(atr, series, prev.index);
        if (!(atrPrev > 0) || Math.abs(prev.value) < minPeakAbsAtr * atrPrev) {
            return;
        }
        int degree = 1;
        if (run.size() >= 3) {
            Extremum p0 = run.get(run.size() - 3);
            boolean declining = p == Polarity.PEAKS_ABOVE_ZERO
                    ? p0.value > prev.value && prev.value > curr.value
                    : p0.value < prev.value && prev.value < curr.value;
            boolean updating = p == Polarity.PEAKS_ABOVE_ZERO
                    ? high(series, p0.index) < highPrev && highPrev < highCurr
                    : high(series, p0.index) > highPrev && highPrev > highCurr;
            if (declining && updating) {
                degree = 2;
            }
        }
        double strength = strength(prev.value, curr.value, dist);
        DivergenceEvent.Status status = status(series, ao, curr.index, p, now);
        Instant nowTs = now < series.getBarCount() ? endTime(series, now) : endTime(series, curr.index);
        Instant peak2Ts = endTime(series, curr.index);
        DivergenceEvent.Direction direction = p == Polarity.PEAKS_ABOVE_ZERO
                ? DivergenceEvent.Direction.BEARISH
                : DivergenceEvent.Direction.BULLISH;
        UUID id = idOf(figi, interval, peak2Ts, direction);
        out.add(DivergenceEvent.builder()
                .id(id)
                .figi(figi)
                .interval(interval)
                .ts(peak2Ts)
                .direction(direction)
                .degree(degree)
                .aoPeak1Ts(endTime(series, prev.index))
                .aoPeak1Value(prev.value)
                .aoPeak2Ts(peak2Ts)
                .aoPeak2Value(curr.value)
                .priceAtPeak1(highPrev)
                .priceAtPeak2(highCurr)
                .strength(strength)
                .status(status)
                .confirmedAt(status == DivergenceEvent.Status.CONFIRMED ? nowTs : null)
                .createdAt(nowTs)
                .build());
    }

    /** Stable id across rescans so the angulation FK / status updates stay consistent. */
    public static UUID idOf(String figi, CandleInterval interval, Instant peak2Ts,
                            DivergenceEvent.Direction direction) {
        return UUID.nameUUIDFromBytes((figi + "|" + interval + "|" + peak2Ts + "|" + direction)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Confirmation: within {@code confirmBars} of the extreme bar the AO turns in the direction of
     * the divergence (two consecutive falling bars for a bearish top, rising bars for a bullish
     * bottom). If the window has not fully elapsed the formation stays UNCONFIRMED; otherwise it
     * is CONFIRMED or EXPIRED.
     */
    private DivergenceEvent.Status status(BarSeries series, AwesomeOscillatorIndicator ao,
                                          int extreme, Polarity p, int now) {
        int windowEnd = Math.min(extreme + confirmBars, series.getBarCount() - 1);
        boolean elapsed = (extreme + confirmBars) <= (series.getBarCount() - 1);
        if (!elapsed) {
            return DivergenceEvent.Status.UNCONFIRMED;
        }
        boolean turned = false;
        for (int k = extreme + 1; k <= windowEnd; k++) {
            double a = ao(k - 2, ao, series);
            double b = ao(k - 1, ao, series);
            double c = ao(k, ao, series);
            if (Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(c)) {
                continue;
            }
            boolean down = c < b && b < a;
            boolean up = c > b && b > a;
            if (p == Polarity.PEAKS_ABOVE_ZERO ? down : up) {
                turned = true;
                break;
            }
        }
        return turned ? DivergenceEvent.Status.CONFIRMED : DivergenceEvent.Status.EXPIRED;
    }

    /** Normalised exhaustion strength in {@code [0,1]}: relative AO drop plus a distance bonus. */
    private static double strength(double peak1, double peak2, int dist) {
        double denom = Math.max(Math.abs(peak1), 1e-9);
        double base = Math.abs(peak1 - peak2) / denom;
        double distFactor = 1.0 - Math.min(1.0, (double) dist / 60.0);
        return Math.max(0.0, Math.min(1.0, base + 0.1 * distFactor));
    }

    private boolean confirmExtremum(BarSeries series, AwesomeOscillatorIndicator ao, int cand,
                                    int now, Polarity p) {
        for (int k = cand + 1; k <= now; k++) {
            double aok = ao(k, ao, series);
            if (Double.isNaN(aok)) {
                return false;
            }
            if (p == Polarity.PEAKS_ABOVE_ZERO ? aok >= ao(cand, ao, series)
                    : aok <= ao(cand, ao, series)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isExtremum(double value, Polarity p) {
        return p == Polarity.PEAKS_ABOVE_ZERO ? value > 0 : value < 0;
    }

    private static double ao(int i, AwesomeOscillatorIndicator ao, BarSeries series) {
        if (i < 0 || i >= series.getBarCount()) {
            return Double.NaN;
        }
        Num v = ao.getValue(i);
        return v == null ? Double.NaN : v.doubleValue();
    }

    private static double atrValue(ATRIndicator atr, BarSeries series, int i) {
        if (i < 0 || i >= series.getBarCount()) {
            return Double.NaN;
        }
        Num v = atr.getValue(i);
        return v == null ? Double.NaN : v.doubleValue();
    }

    private static double high(BarSeries series, int i) {
        Bar b = series.getBar(i);
        return b == null ? Double.NaN : b.getHighPrice().doubleValue();
    }

    private static Instant endTime(BarSeries series, int i) {
        Bar b = series.getBar(i);
        return b == null ? Instant.EPOCH : b.getEndTime();
    }

    private record Extremum(int index, double value) {
    }
}