package com.traderbro.core.indicators;

import static org.assertj.core.api.Assertions.assertThat;

import com.traderbro.api.config.WilliamsProperties;
import com.traderbro.core.domain.AngulationEvent;
import com.traderbro.core.domain.DivergenceEvent;
import com.traderbro.core.domain.enums.CandleInterval;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;

/** Synthetic tests for the price-to-Jaw angulation detector (both modes). */
class AngulationDetectorTest {

    private static final Duration PERIOD = Duration.ofHours(1);
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static AngulationDetector detector() {
        return new AngulationDetector(new WilliamsProperties().getAngulation());
    }

    private static BarSeries departUpSeries() {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            closes.add(100.0);                 // flat warm-up: price touches the Jaw
        }
        for (int i = 1; i <= 20; i++) {
            closes.add(100 + i * 2.5);         // strong monotonic rise away from the Jaw
        }
        return series(closes);
    }

    private static BarSeries flatSeries() {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            closes.add(100.0);
        }
        return series(closes);
    }

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

    @Test
    void detectsDepartureUpOnDivergencePeak() {
        BarSeries s = departUpSeries();
        AlligatorIndicator all = new AlligatorIndicator(s, 13, 8, 8, 5, 5, 3, 14, 0.5);
        ATRIndicator atr = all.getAtr();
        Instant last = s.getLastBar().getEndTime();
        DivergenceEvent div = DivergenceEvent.builder().id(UUID.randomUUID()).figi("TEST")
                .interval(CandleInterval.ONE_HOUR).ts(last)
                .direction(DivergenceEvent.Direction.BEARISH).degree(1)
                .aoPeak1Ts(s.getBar(28).getEndTime()).aoPeak1Value(10)
                .aoPeak2Ts(last).aoPeak2Value(6)
                .priceAtPeak1(100).priceAtPeak2(150).strength(0.4)
                .status(DivergenceEvent.Status.CONFIRMED).createdAt(Instant.now()).build();

        Optional<AngulationEvent> res = detector().detect(s, all, atr, div, div.getId());

        assertThat(res).isPresent();
        AngulationEvent e = res.get();
        assertThat(e.getDirection()).isEqualTo(AngulationEvent.Direction.AWAY_UP);
        assertThat(e.getAngleAtr()).isGreaterThanOrEqualTo(0.1);
        assertThat(e.getBarsInWindow()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void flatPriceParallelToJawHasNoAngulation() {
        BarSeries s = flatSeries();
        AlligatorIndicator all = new AlligatorIndicator(s, 13, 8, 8, 5, 5, 3, 14, 0.5);
        ATRIndicator atr = all.getAtr();
        Instant last = s.getLastBar().getEndTime();
        DivergenceEvent div = DivergenceEvent.builder().id(UUID.randomUUID()).figi("TEST")
                .interval(CandleInterval.ONE_HOUR).ts(last)
                .direction(DivergenceEvent.Direction.BEARISH).degree(1)
                .aoPeak1Ts(s.getBar(28).getEndTime()).aoPeak1Value(10)
                .aoPeak2Ts(last).aoPeak2Value(6)
                .priceAtPeak1(100).priceAtPeak2(100).strength(0.4)
                .status(DivergenceEvent.Status.CONFIRMED).createdAt(Instant.now()).build();

        Optional<AngulationEvent> res = detector().detect(s, all, atr, div, div.getId());

        // Price never leaves the Jaw → no angulation event.
        assertThat(res).isEmpty();
    }

    @Test
    void backgroundAnglePresentOnDeparture() {
        BarSeries s = departUpSeries();
        AlligatorIndicator all = new AlligatorIndicator(s, 13, 8, 8, 5, 5, 3, 14, 0.5);
        Optional<AngulationDetector.BackgroundAngle> res =
                detector().backgroundAngle(s, all, all.getAtr(), CandleInterval.ONE_HOUR);

        assertThat(res).isPresent();
        assertThat(res.get().direction()).isEqualTo(AngulationEvent.Direction.AWAY_UP);
        assertThat(res.get().angleAtr()).isGreaterThanOrEqualTo(0.1);
    }
}