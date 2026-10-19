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
import org.ta4j.core.BaseBarSeriesBuilder;
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
            case FOUR_HOUR -> Duration.ofHours(4);
            case ONE_DAY -> Duration.ofDays(1);
        };
    }

    /**
     * Builds a windowed series (bounded memory) from domain bars.
     *
     * @param numFactory e.g. {@code DoubleNum::valueOf} for the live engine or
     *                   {@code DecimalNum::valueOf} for backtesting
     * @param maxBars    maximum bars retained in memory (window)
     */
    public static BarSeries fromDomain(List<Bar> bars, Function<Number, Num> numFactory,
                                       int maxBars, String figi) {
        List<org.ta4j.core.Bar> ta4jBars = new java.util.ArrayList<>();
        for (Bar bar : bars) {
            if (!bar.isFinal_()) {
                // Forming candles must never enter the decision series.
                continue;
            }
            ta4jBars.add(toTa4jBar(bar, numFactory));
        }
        // Cap up-front: handing BaseBarSeriesBuilder more bars than maxBarCount makes ta4j
        // misreport indices (every bar resolves to the same one). Keep the most recent maxBars.
        if (ta4jBars.size() > maxBars) {
            ta4jBars = new java.util.ArrayList<>(ta4jBars.subList(ta4jBars.size() - maxBars, ta4jBars.size()));
        }
        // withBars derives the series numFactory from the first bar, so the series type always
        // matches the bar type (the default DecimalNum would otherwise reject DoubleNum bars).
        BaseBarSeries series = new BaseBarSeriesBuilder()
                .withName(figi)
                .withMaxBarCount(maxBars)
                .withBars(ta4jBars)
                .build();
        return series;
    }

    /** Converts a single closed domain bar into a ta4j bar. */
    public static BaseBar toTa4jBar(Bar bar, Function<Number, Num> numFactory) {
        Duration period = toDuration(bar.getInterval());
        Instant end = bar.getTs();
        return new BaseBar(period, end.minus(period), end,
                numFactory.apply(bd(bar.getOpen())),
                numFactory.apply(bd(bar.getHigh())),
                numFactory.apply(bd(bar.getLow())),
                numFactory.apply(bd(bar.getClose())),
                numFactory.apply(bar.getVolume()),
                numFactory.apply(0), 0);
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

    /** End time (Instant) of the series' last bar, or null when empty. */
    public static Instant lastEndTime(BarSeries series) {
        if (series.isEmpty()) {
            return null;
        }
        return series.getLastBar().getEndTime();
    }
}