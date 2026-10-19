package com.traderbro.core.indicators;

import static org.assertj.core.api.Assertions.assertThat;

import com.traderbro.core.domain.Bar;
import com.traderbro.testutil.BarTestData;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.num.DecimalNum;

class SeriesFactoryTest {

    @Test
    void fromDomainKeepsLastMaxBarsDistinctWhenInputExceedsCap() {
        List<Bar> bars = BarTestData.domainBars(
                IntStream.range(0, 700).mapToDouble(i -> i + 1.0).boxed().collect(Collectors.toList()),
                "F");

        BarSeries series = SeriesFactory.fromDomain(bars, DecimalNum::valueOf, 500, "F");

        // Regression: passing more bars than maxBarCount used to make ta4j repeat the same bar,
        // so the series contained 500 identical bars instead of the last 500 distinct ones.
        assertThat(series.getBarCount()).isEqualTo(500);
        Set<Instant> ends = new HashSet<>();
        for (int i = 0; i < series.getBarCount(); i++) {
            ends.add(series.getBar(i).getEndTime());
        }
        assertThat(ends).hasSize(500);
        // The most recent input bar must be retained as the series' last bar.
        assertThat(series.getLastBar().getEndTime()).isEqualTo(bars.get(699).getTs());
    }

    @Test
    void fromDomainKeepsAllWhenInputWithinCap() {
        List<Bar> bars = BarTestData.domainBars(
                IntStream.range(0, 100).mapToDouble(i -> i + 1.0).boxed().collect(Collectors.toList()),
                "F");

        BarSeries series = SeriesFactory.fromDomain(bars, DecimalNum::valueOf, 500, "F");

        assertThat(series.getBarCount()).isEqualTo(100);
        assertThat(series.getLastBar().getEndTime()).isEqualTo(bars.get(99).getTs());
    }
}