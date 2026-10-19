package com.traderbro.core.indicators;

import static org.assertj.core.api.Assertions.assertThat;

import com.traderbro.api.config.WilliamsProperties;
import com.traderbro.core.domain.DivergenceEvent;
import com.traderbro.core.domain.enums.CandleInterval;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.ta4j.core.Bar;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.BarSeries;

/**
 * Synthetic tests for the AO "Twin Peaks" divergence detector. Bars carry a small high/low range
 * so ATR > 0 and the min-peak-amplitude (ATR-relative) gate can be exercised.
 */
class AoTwinPeaksDetectorTest {

    private static final Duration PERIOD = Duration.ofHours(1);
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static WilliamsProperties cfg() {
        return new WilliamsProperties();
    }

    /** Builds a 1h series where close = median and high/low carry a fixed spread. */
    private static BarSeries series(List<Double> closes) {
        BarSeries s = new BaseBarSeriesBuilder().withName("TEST").build();
        for (int i = 0; i < closes.size(); i++) {
            Instant end = T0.plus(PERIOD.multipliedBy(i + 1));
            double m = closes.get(i);
            s.addBar(new BaseBar(PERIOD, end.minus(PERIOD), end,
                    s.numFactory().numOf(m), s.numFactory().numOf(m + 0.5),
                    s.numFactory().numOf(m - 0.5), s.numFactory().numOf(m),
                    s.numFactory().numOf(1000), s.numFactory().numOf(0), 0));
        }
        return s;
    }

    /** Price rises fast to a high (AO peak1), pulls back, then grinds slowly to a new high
     * (weaker AO peak2) and finally rolls over → bearish Twin Peaks with confirmation. */
    private static List<Double> bearishCloses() {
        List<Double> c = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            c.add(100.0);                        // flat warm-up (AO ≈ 0)
        }
        for (int i = 1; i <= 15; i++) {
            c.add(100 + i * 2.6);                // fast rise → peak1 (up to 139)
        }
        for (int i = 1; i <= 7; i++) {
            c.add(139 - i * 2.5);                // pullback (still positive AO)
        }
        for (int i = 1; i <= 26; i++) {
            c.add(121 + i * 0.9);                // slow grind to new high 144.4 (weak AO peak2)
        }
        for (int i = 1; i <= 4; i++) {
            c.add(144 - i * 1.5);                // rollover → confirmation
        }
        return c;
    }

    @Test
    void detectsConfirmedBearishTwinPeaks() {
        BarSeries s = series(bearishCloses());
        AwesomeOscillatorIndicator ao = new AwesomeOscillatorIndicator(s);
        AoTwinPeaksDetector d = new AoTwinPeaksDetector(cfg().getDivergence());

        List<DivergenceEvent> evts = d.scan(s, ao, CandleInterval.ONE_HOUR, "TEST");
        DivergenceEvent bear = evts.stream()
                .filter(e -> e.getDirection() == DivergenceEvent.Direction.BEARISH)
                .filter(e -> e.getStatus() == DivergenceEvent.Status.CONFIRMED)
                .findFirst().orElse(null);

        assertThat(bear).isNotNull();
        // Exhaustion: second AO peak weaker in absolute value; price made a new high.
        assertThat(bear.getAoPeak2Value()).isLessThan(bear.getAoPeak1Value());
        assertThat(bear.getPriceAtPeak2()).isGreaterThan(bear.getPriceAtPeak1());
        assertThat(bear.getStatus()).isEqualTo(DivergenceEvent.Status.CONFIRMED);
    }

    @Test
    void doesNotFireBeforePeakConfirmation() {
        BarSeries full = series(bearishCloses());
        AwesomeOscillatorIndicator ao = new AwesomeOscillatorIndicator(full);
        AoTwinPeaksDetector d = new AoTwinPeaksDetector(cfg().getDivergence());

        // Find the full-scan event to learn the extreme bar, then truncate before its AO peak
        // confirmation lag and confirm the detector emits nothing (look-ahead guard).
        List<DivergenceEvent> fullEvts = d.scan(full, ao, CandleInterval.ONE_HOUR, "TEST");
        assertThat(fullEvts).isNotEmpty();
        DivergenceEvent extreme = fullEvts.get(fullEvts.size() - 1);

        int extremeIdx = indexOf(full, extreme.getTs());
        // Truncate so the extreme bar's confirmation window hasn't elapsed.
        BarSeries truncated = full.getSubSeries(0, extremeIdx + 1);
        AwesomeOscillatorIndicator tAo = new AwesomeOscillatorIndicator(truncated);
        List<DivergenceEvent> before = d.scan(truncated, tAo, CandleInterval.ONE_HOUR, "TEST");
        boolean confirmedEarly = before.stream()
                .anyMatch(e -> e.getStatus() == DivergenceEvent.Status.CONFIRMED
                        && e.getTs().equals(extreme.getTs()));
        assertThat(confirmedEarly).isFalse();
    }

    @Test
    void zeroCrossResetsFormation() {
        // A fast rise then a hard drop through zero then another rise to a new high: the dip
        // crosses zero, so the two peaks are NOT on the same side → no divergence.
        List<Double> c = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            c.add(100.0);
        }
        for (int i = 1; i <= 15; i++) {
            c.add(100 + i * 3.0);
        }
        for (int i = 1; i <= 12; i++) {
            c.add(145 - i * 12.0);               // collapse well below the SMA34 baseline (zero cross)
        }
        for (int i = 1; i <= 20; i++) {
            c.add(60 + i * 2.0);                 // recover to a new high on a fresh above-zero run
        }
        BarSeries s = series(c);
        AwesomeOscillatorIndicator ao = new AwesomeOscillatorIndicator(s);
        AoTwinPeaksDetector d = new AoTwinPeaksDetector(cfg().getDivergence());

        List<DivergenceEvent> evts = d.scan(s, ao, CandleInterval.ONE_HOUR, "TEST");
        // The pre-cross and post-cross runs are separate; no single pair spans the zero crossing.
        assertThat(evts.stream().anyMatch(e -> e.getDirection() == DivergenceEvent.Direction.BEARISH
                && e.getStatus() != DivergenceEvent.Status.EXPIRED)).isFalse();
    }

    private static int indexOf(BarSeries s, Instant ts) {
        for (int i = 0; i < s.getBarCount(); i++) {
            if (s.getBar(i).getEndTime().equals(ts)) {
                return i;
            }
        }
        return -1;
    }
}