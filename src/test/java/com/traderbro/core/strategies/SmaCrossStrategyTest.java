package com.traderbro.core.strategies;

import static org.assertj.core.api.Assertions.assertThat;

import com.traderbro.testutil.BarTestData;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;

class SmaCrossStrategyTest {

    private final SmaCrossStrategy strategy = new SmaCrossStrategy(4);
    private final NoOpSignalFilter filter = new NoOpSignalFilter();

    private StrategyParams params() {
        Map<String, String> m = new HashMap<>();
        m.put("fast", "20");
        m.put("slow", "100");
        return new StrategyParams(m);
    }

    @Test
    void buildsValidTa4jStrategy() {
        BarSeries series = BarTestData.risingSeries(150, 100, 1.0, "F");
        org.ta4j.core.Strategy s = strategy.build(series, params());
        assertThat(s.getEntryRule()).isNotNull();
        assertThat(s.getExitRule()).isNotNull();
    }

    @Test
    void noSignalWhenNoCrossAtLastBar() {
        // Strictly declining -> fast below slow, no up-cross at the end.
        BarSeries series = BarTestData.risingSeries(150, 500, -1.0, "F");
        Optional<com.traderbro.core.domain.Signal> signal =
                strategy.evaluate(series, params(), filter);
        assertThat(signal).isEmpty();
    }

    @Test
    void emitsSignalOnCrossUp() {
        // Flat for warmup, then a sharp rise so SMA20 crosses up over SMA100 near the end.
        BarSeries series = BarTestData.seriesFrom(i -> {
            if (i < 110) {
                return 100.0;
            }
            return 100.0 + (i - 109) * 10.0; // steep rise
        }, 150, "F");
        Optional<com.traderbro.core.domain.Signal> signal =
                strategy.evaluate(series, params(), filter);
        assertThat(signal).isPresent();
        assertThat(signal.get().getDirection()).isEqualTo(com.traderbro.core.domain.enums.Direction.LONG);
        assertThat(signal.get().getIndicatorSnapshot()).containsKey("sma_fast");
    }

    @Test
    void rejectsInvalidParams() {
        Map<String, String> bad = new HashMap<>();
        bad.put("fast", "100");
        bad.put("slow", "20");
        BarSeries series = BarTestData.risingSeries(150, 100, 1.0, "F");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> strategy.build(series, new StrategyParams(bad)));
    }
}