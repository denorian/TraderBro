package com.traderbro.testutil;

import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.indicators.SeriesFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.ta4j.core.BarSeries;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.DoubleNum;

/** Helpers for building synthetic bars/series in tests. */
public final class BarTestData {

    private BarTestData() {
    }

    /** Builds domain bars with the given close prices (open=high=low=close), daily, ascending. */
    public static List<Bar> domainBars(List<Double> closes, String figi) {
        List<Bar> bars = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        for (int i = 0; i < closes.size(); i++) {
            BigDecimal c = BigDecimal.valueOf(closes.get(i));
            bars.add(Bar.builder()
                    .figi(figi)
                    .ts(base.plusSeconds(i * 86400L))
                    .interval(CandleInterval.ONE_DAY)
                    .open(c).high(c).low(c).close(c)
                    .volume(1000)
                    .final_(true)
                    .build());
        }
        return bars;
    }

    /** Builds a ta4j series from domain bars using DecimalNum. */
    public static BarSeries series(List<Bar> bars, String figi) {
        return SeriesFactory.fromDomain(bars, DecimalNum::valueOf, bars.size() + 10, figi);
    }

    /** Builds a series of N linearly-rising close prices. */
    public static BarSeries risingSeries(int n, double start, double step, String figi) {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            closes.add(start + i * step);
        }
        return series(domainBars(closes, figi), figi);
    }

    /** Builds a series from a per-index close generator. */
    public static BarSeries seriesFrom(Function<Integer, Double> closeFn, int n, String figi) {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            closes.add(closeFn.apply(i));
        }
        return series(domainBars(closes, figi), figi);
    }
}