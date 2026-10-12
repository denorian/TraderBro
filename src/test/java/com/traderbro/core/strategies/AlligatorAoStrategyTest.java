package com.traderbro.core.strategies;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.traderbro.core.indicators.IndicatorFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Strategy;

/** Smoke tests for {@link AlligatorAoStrategy}. */
class AlligatorAoStrategyTest {

    private static final Duration PERIOD = Duration.ofHours(1);
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final SignalFilter ACCEPTING = (figi, dir) -> 1.0;

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

    private AlligatorAoStrategy strategy() {
        return new AlligatorAoStrategy(4, IndicatorFactory.AlligatorParams.williamsDefaults());
    }

    @Test
    void noEntryWhileAlligatorSleeping() {
        BarSeries s = flatSeries(60, 100);
        AlligatorAoStrategy strategy = strategy();
        // A flat series keeps the Alligator in SLEEPING → no entry regardless of AO.
        Optional<com.traderbro.core.domain.Signal> signal =
                strategy.evaluate(s, new StrategyParams(java.util.Map.of()), ACCEPTING);
        assertTrue(signal.isEmpty());
    }

    @Test
    void buildProducesAStrategy() {
        BarSeries s = flatSeries(60, 100);
        Strategy built = strategy().build(s, new StrategyParams(java.util.Map.of()));
        assertNotNull(built);
    }

    @Test
    void stableId() {
        assertTrue(AlligatorAoStrategy.ID.equals("AlligatorAoStrategy"));
    }

    /** Minimal filter accepting everything. */
    interface SignalFilter extends com.traderbro.core.domain.spi.SignalFilter {
    }
}