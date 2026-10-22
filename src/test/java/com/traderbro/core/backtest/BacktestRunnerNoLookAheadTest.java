package com.traderbro.core.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.traderbro.core.domain.Instrument;
import com.traderbro.core.strategies.SmaCrossStrategy;
import com.traderbro.core.strategies.StrategyParams;
import com.traderbro.testutil.BarTestData;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;

/**
 * Backtest correctness: determinism, flat/no-trade case, and the explicit no-look-ahead
 * invariant (decisions on closed bars executed at the next open; future bars must not change
 * past decisions).
 */
class BacktestRunnerNoLookAheadTest {

    private final BacktestRunner runner = new BacktestRunner(4);
    private final Instrument instrument = Instrument.builder().figi("F").ticker("X").lot(10)
            .minPriceIncrement(BigDecimal.ONE).tradable(true).build();
    private final SmaCrossStrategy strategy = new SmaCrossStrategy(4);

    private StrategyParams params() {
        Map<String, String> m = new HashMap<>();
        m.put("fast", "5");
        m.put("slow", "20");
        return new StrategyParams(m);
    }

    private BacktestRunner.BacktestResult run(BarSeries series) {
        return runner.runDetailed(strategy, series, params(), instrument,
                new BigDecimal("0.0005"), new BigDecimal("5"),
                new BigDecimal("100000"), new BigDecimal("0.95"));
    }

    @Test
    void isDeterministic() {
        BarSeries series = BarTestData.seriesFrom(i -> 100 + 5 * Math.sin(2 * Math.PI * i / 30.0), 200, "F");
        BacktestMetrics a = run(series).metrics();
        BacktestMetrics b = run(series).metrics();
        assertThat(a.totalReturn()).isEqualTo(b.totalReturn());
        assertThat(a.sharpe()).isEqualTo(b.sharpe());
        assertThat(a.numTrades()).isEqualTo(b.numTrades());
    }

    @Test
    void flatSeriesProducesNoTrades() {
        BarSeries flat = BarTestData.risingSeries(150, 100, 0.0, "F");
        BacktestRunner.BacktestResult result = run(flat);
        assertThat(result.trades()).isEmpty();
        assertThat(result.metrics().numTrades()).isZero();
    }

    @Test
    void waveSeriesProducesTrades() {
        BarSeries wave = BarTestData.seriesFrom(i -> 100 + 5 * Math.sin(2 * Math.PI * i / 30.0), 200, "F");
        assertThat(run(wave).trades()).isNotEmpty();
    }

    /**
     * No-look-ahead: appending future bars must not change any decision (entry bar + entry
     * equity) made within the shared prefix.
     */
    @Test
    void noLookAhead() {
        List<Double> baseCloses = closes(200);
        List<Double> extendedCloses = closes(250); // same first 200 + 50 future bars

        var base = runner.runDetailed(strategy, BarTestData.series(extendedSeries(baseCloses), "F"),
                params(), instrument, new BigDecimal("0.0005"), new BigDecimal("5"),
                new BigDecimal("100000"), new BigDecimal("0.95"));
        var extended = runner.runDetailed(strategy, BarTestData.series(extendedSeries(extendedCloses), "F"),
                params(), instrument, new BigDecimal("0.0005"), new BigDecimal("5"),
                new BigDecimal("100000"), new BigDecimal("0.95"));

        // All base trades have entryBar within the shared prefix [0..199].
        List<String> baseKeys = base.trades().stream()
                .map(t -> t.getEntryBar() + ":" + t.getEntryEquity())
                .toList();
        List<String> extKeys = extended.trades().stream()
                .filter(t -> t.getEntryBar() <= 199)
                .map(t -> t.getEntryBar() + ":" + t.getEntryEquity())
                .toList();
        assertThat(extKeys).containsExactlyElementsOf(baseKeys);
    }

    /**
     * Slippage is adverse on both legs: a larger value must never improve returns. This is
     * sensitive to the sign on the exit fill — selling above the market (the pre-fix bug) would
     * inflate profits as slippage grows.
     */
    @Test
    void higherSlippageNeverImprovesReturns() {
        BarSeries wave = BarTestData.seriesFrom(i -> 100 + 5 * Math.sin(2 * Math.PI * i / 30.0), 200, "F");
        double noSlip = runner.runDetailed(strategy, wave, params(), instrument,
                new BigDecimal("0.0005"), BigDecimal.ZERO,
                new BigDecimal("100000"), new BigDecimal("0.95")).metrics().totalReturn();
        double highSlip = runner.runDetailed(strategy, wave, params(), instrument,
                new BigDecimal("0.0005"), new BigDecimal("50"),
                new BigDecimal("100000"), new BigDecimal("0.95")).metrics().totalReturn();
        assertThat(highSlip).isLessThanOrEqualTo(noSlip);
    }


    private static List<Double> closes(int n) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> 100 + 5 * Math.sin(2 * Math.PI * i / 30.0))
                .toList();
    }

    private static List<com.traderbro.core.domain.Bar> extendedSeries(List<Double> closes) {
        return BarTestData.domainBars(closes, "F");
    }
}