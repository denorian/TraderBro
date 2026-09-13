package com.traderbro.core.indicators;

import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.enums.CandleInterval;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.num.Num;

/**
 * Builds ta4j {@link BarSeries} instances from domain {@link Bar}s, isolating the
 * strategy engine from the storage/data formats.
 */
public final class SeriesFactory {

    private SeriesFactory() {
    }

    /** Maps a domain interval to a {@link Duration}, used for bar time periods. */
    public static Duration toDuration(CandleInterval interval) {
        return switch (interval) {
            case ONE_MIN -> Duration.ofMinutes(1);
            case FIVE_MIN -> Duration.ofMinutes(5);
            case FIFTEEN_MIN -> Duration.ofMinutes(15);
            case ONE_HOUR -> Duration.ofHours(1);
            case ONE_DAY -> Duration.ofDays(1);
        };
    }

    /**
     * Builds a windowed series (bounded memory) from domain bars.
     *
     * @param bars      closed bars, ascending by time
     * @param numFactory e.g. {@code DoubleNum::valueOf} for the live engine or
     *                  {@code DecimalNum::valueOf} for backtesting
     * @param maxBars   maximum bars retained in memory (window)
     * @param figi      series name
     */
    public static BarSeries fromDomain(List<Bar> bars, Function<Number, Num> numFactory,
                                       int maxBars, String figi) {
        BaseBarSeries series = BaseBarSeries.builder()
                .withName(figi)
                .withMaxBarCount(maxBars)
                .build();
        for (Bar bar : bars) {
            if (!bar.isFinal_()) {
                // Forming candles must never enter the decision series.
                continue;
            }
            series.addBar(toTa4jBar(bar, numFactory), false);
        }
        return series;
    }

    /** Converts a single closed domain bar into a ta4j bar. */
    public static BaseBar toTa4jBar(Bar bar, Function<Number, Num> numFactory) {
        return BaseBar.builder()
                .timePeriod(toDuration(bar.getInterval()))
                .endTime(org.ta4j.core.Timestamp.from(bar.getTs()))
                .openPrice(numFactory.apply(bd(bar.getOpen())))
                .highPrice(numFactory.apply(bd(bar.getHigh())))
                .lowPrice(numFactory.apply(bd(bar.getLow())))
                .closePrice(numFactory.apply(bd(bar.getClose())))
                .volume(numFactory.apply(bar.getVolume()))
                .build();
    }

    private static Number bd(BigDecimal v) {
        return v;
    }

    /** Helper to read the latest value of an indicator as a double. */
    public static double last(Indicator<Num> indicator) {
        Num v = indicator.getValue(indicator.getBarSeries().getEndIndex());
        return v == null ? Double.NaN : v.doubleValue();
    }

    /** Helper: whether a value is available at the given index (not NaN/empty). */
    public static boolean isPresent(Indicator<Num> indicator, int index) {
        if (index < 0 || index >= indicator.getBarSeries().getBarCount()) {
            return false;
        }
        Num v = indicator.getValue(index);
        return v != null && !Double.isNaN(v.doubleValue());
    }

    /** Unix epoch millis of the series' last bar end time, or null when empty. */
    public static Instant lastEndTime(BarSeries series) {
        if (series.isEmpty()) {
            return null;
        }
        return series.getLastBar().getEndTime().toInstant();
    }
}