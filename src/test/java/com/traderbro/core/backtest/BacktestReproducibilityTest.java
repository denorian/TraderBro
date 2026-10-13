package com.traderbro.core.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.domain.enums.InstrumentType;
import com.traderbro.core.indicators.SeriesFactory;
import com.traderbro.core.strategies.SmaCrossStrategy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.num.DecimalNum;

/**
 * Reproducibility: two simulations over identical data and parameters must produce identical
 * metrics (the run is deterministic, no randomness).
 */
class BacktestReproducibilityTest {

    @Test
    void identicalInputsProduceIdenticalMetrics() {
        List<Bar> bars = bars(400);
        Instrument inst = Instrument.builder().figi("F").ticker("SBER").name("x").lot(10)
                .minPriceIncrement(new BigDecimal("0.01")).tradable(true)
                .instrumentType(InstrumentType.SHARE).build();
        BarSeries s1 = SeriesFactory.fromDomain(bars, DecimalNum::valueOf, 500, "F");
        BarSeries s2 = SeriesFactory.fromDomain(bars, DecimalNum::valueOf, 500, "F");

        BacktestRunner runner = new BacktestRunner(4);
        SimulationResult a = runner.simulateShares(new SmaCrossStrategy(4), s1,
                new com.traderbro.core.strategies.StrategyParams(java.util.Map.of("fast", "20", "slow", "100")),
                inst, new BigDecimal("0.0005"), BigDecimal.valueOf(5),
                new BigDecimal("1000000"), new BigDecimal("0.95"));
        SimulationResult b = runner.simulateShares(new SmaCrossStrategy(4), s2,
                new com.traderbro.core.strategies.StrategyParams(java.util.Map.of("fast", "20", "slow", "100")),
                inst, new BigDecimal("0.0005"), BigDecimal.valueOf(5),
                new BigDecimal("1000000"), new BigDecimal("0.95"));

        assertEquals(a.metrics(), b.metrics());
        assertEquals(a.trades().size(), b.trades().size());
        assertEquals(a.signals().size(), b.signals().size());
    }

    private List<Bar> bars(int n) {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        List<Bar> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double m = 100 + Math.sin(i / 7.0) * 5 + i * 0.05;
            out.add(Bar.builder().figi("F").interval(CandleInterval.ONE_HOUR)
                    .ts(start.plus(Duration.ofHours(i)))
                    .open(BigDecimal.valueOf(m)).high(BigDecimal.valueOf(m + 1))
                    .low(BigDecimal.valueOf(m - 1)).close(BigDecimal.valueOf(m))
                    .volume(100).final_(true).build());
        }
        return out;
    }
}