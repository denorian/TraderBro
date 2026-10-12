package com.traderbro.core.indicators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.traderbro.core.indicators.AlligatorIndicator.AlligatorState;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.BarSeries;
import org.ta4j.core.num.Num;

/**
 * Control-value tests for the Williams indicators. Uses a linear median series so the SMAs and
 * derived values are easy to compute by hand, plus a flat series for the SLEEPING classifier.
 */
class WilliamsIndicatorsTest {

    private static final Duration PERIOD = Duration.ofHours(1);
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** median(i) = 10*i → high=10i+1, low=10i-1 so (H+L)/2 = 10i. */
    private static BarSeries linearSeries(int bars) {
        BarSeries s = new BaseBarSeriesBuilder().withName("test").build();
        for (int i = 0; i < bars; i++) {
            double m = 10.0 * i;
            Instant end = T0.plus(PERIOD.multipliedBy(i + 1));
            s.addBar(new BaseBar(PERIOD, end.minus(PERIOD), end,
                    s.numFactory().numOf(m - 0.5),
                    s.numFactory().numOf(m + 1),
                    s.numFactory().numOf(m - 1),
                    s.numFactory().numOf(m + 0.5),
                    s.numFactory().numOf(100),
                    s.numFactory().numOf(0), 0));
        }
        return s;
    }

    private static BarSeries flatSeries(int bars, double m) {
        BarSeries s = new BaseBarSeriesBuilder().withName("test").build();
        for (int i = 0; i < bars; i++) {
            Instant end = T0.plus(PERIOD.multipliedBy(i + 1));
            s.addBar(new BaseBar(PERIOD, end.minus(PERIOD), end,
                    s.numFactory().numOf(m),
                    s.numFactory().numOf(m + 1),
                    s.numFactory().numOf(m - 1),
                    s.numFactory().numOf(m),
                    s.numFactory().numOf(100),
                    s.numFactory().numOf(0), 0));
        }
        return s;
    }

    @Test
    void medianPriceIsHalfOfHighPlusLow() {
        BarSeries s = linearSeries(5);
        MedianPriceIndicator median = new MedianPriceIndicator(s);
        // bar 3: high=31, low=29 → median 30
        assertEquals(30.0, median.getValue(3).doubleValue(), 1e-9);
    }

    @Test
    void awesomeOscillatorIsFastSmaMinusSlowSma() {
        BarSeries s = linearSeries(60);
        AwesomeOscillatorIndicator ao = new AwesomeOscillatorIndicator(s, 5, 34);
        // For a linear series the AO is constant once the slow window is full: 145.
        assertEquals(145.0, ao.getValue(40).doubleValue(), 1e-9);
        assertEquals(145.0, ao.getValue(59).doubleValue(), 1e-9);
        // rising compares current vs previous bar — constant AO is not rising.
        assertTrue(!ao.isRising(40));
    }

    @Test
    void alligatorLinesMatchUnshiftedSmas() {
        BarSeries s = linearSeries(60);
        AlligatorIndicator all = new AlligatorIndicator(s, 13, 8, 8, 5, 5, 3, 14, 0.5);
        // raw jaw = SMA(13) of median; for linear medians = 10*i - 60.
        assertEquals(10.0 * 40 - 60, all.jawRaw(40).doubleValue(), 1e-9);
        // shifted jaw at bar i == raw jaw at bar i-8 (no look-ahead).
        assertEquals(all.jawRaw(32).doubleValue(), all.jaw(40).doubleValue(), 1e-9);
        assertEquals(all.jawRaw(39).doubleValue(), all.jaw(47).doubleValue(), 1e-9);
    }

    @Test
    void noLookAheadShiftedLinesUseOnlyPastData() {
        BarSeries s = linearSeries(60);
        AlligatorIndicator all = new AlligatorIndicator(s, 13, 8, 8, 5, 5, 3, 14, 0.5);
        // The shifted lines must equal the unshifted line at (i - shift), i.e. never read bar > i.
        for (int i = 20; i < 50; i++) {
            assertEquals(all.jawRaw(i - 8).doubleValue(), all.jaw(i).doubleValue(), 1e-9);
            assertEquals(all.teethRaw(i - 5).doubleValue(), all.teeth(i).doubleValue(), 1e-9);
            assertEquals(all.lipsRaw(i - 3).doubleValue(), all.lips(i).doubleValue(), 1e-9);
        }
    }

    @Test
    void flatSeriesIsSleeping() {
        BarSeries s = flatSeries(60, 100);
        AlligatorIndicator all = new AlligatorIndicator(s, 13, 8, 8, 5, 5, 3, 14, 0.5);
        assertEquals(AlligatorState.SLEEPING, all.state(40));
        assertEquals(AlligatorState.SLEEPING, all.state(59));
    }

    @Test
    void linearTrendIsEatingBull() {
        BarSeries s = linearSeries(60);
        AlligatorIndicator all = new AlligatorIndicator(s, 13, 8, 8, 5, 5, 3, 14, 0.5);
        // lips(10i-20) > teeth(10i-35) > jaw(10i-120) with spread >> threshold → eating bull.
        assertEquals(AlligatorState.EATING_BULL, all.state(40));
        assertTrue(all.fanWidth(40) > 0);
    }

    @Test
    void fractalReturnsTrailingExtremum() {
        BarSeries s = linearSeries(60);
        FractalIndicator high = new FractalIndicator(s, FractalIndicator.Type.HIGH, 5);
        // trailing max high over last 5 bars = high of current bar = 10*40+1.
        assertEquals(401.0, high.getValue(40).doubleValue(), 1e-9);
        FractalIndicator low = new FractalIndicator(s, FractalIndicator.Type.LOW, 5);
        assertEquals(10.0 * 36 - 1, low.getValue(40).doubleValue(), 1e-9);
    }

    @Test
    void saucerDetection() {
        // A 5-bar dip (larger than the slow window's smoothing) forces the AO into a trough,
        // so the middle bar of the last three is lower than both neighbours while staying > 0.
        double[] medians = new double[60];
        for (int i = 0; i < 60; i++) {
            medians[i] = 10.0 * i;
        }
        for (int i = 40; i <= 44; i++) {
            medians[i] = 10.0 * i - 100;   // local trough spanning bars 40..44
        }
        BarSeries s = new BaseBarSeriesBuilder().withName("test").build();
        for (int i = 0; i < 60; i++) {
            double m = medians[i];
            Instant end = T0.plus(PERIOD.multipliedBy(i + 1));
            s.addBar(new BaseBar(PERIOD, end.minus(PERIOD), end,
                    s.numFactory().numOf(m - 0.5),
                    s.numFactory().numOf(m + 1),
                    s.numFactory().numOf(m - 1),
                    s.numFactory().numOf(m + 0.5),
                    s.numFactory().numOf(100),
                    s.numFactory().numOf(0), 0));
        }
        AwesomeOscillatorIndicator ao = new AwesomeOscillatorIndicator(s, 5, 34);
        // AO trough at bar 44: below bar 43 and bar 45, all positive → bullish saucer at 45.
        assertTrue(ao.getValue(44).doubleValue() > 0);
        assertTrue(ao.getValue(44).doubleValue() < ao.getValue(43).doubleValue());
        assertTrue(ao.getValue(44).doubleValue() < ao.getValue(45).doubleValue());
        assertTrue(ao.isBullishSaucer(45));
        // A bearish saucer should not fire on a rising series.
        assertTrue(!ao.isBearishSaucer(45));
    }
}