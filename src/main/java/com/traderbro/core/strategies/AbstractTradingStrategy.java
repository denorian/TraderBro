package com.traderbro.core.strategies;

import com.traderbro.core.domain.Signal;
import com.traderbro.core.domain.enums.Direction;
import com.traderbro.core.domain.enums.SignalVerdict;
import com.traderbro.core.domain.spi.SignalFilter;
import com.traderbro.core.indicators.SeriesFactory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.num.Num;

/**
 * Shared scaffolding for stage-1 strategies: signal construction, indicator snapshots and
 * price extraction. Subclasses implement {@link #id()} and the two build/evaluate methods.
 */
public abstract class AbstractTradingStrategy implements TradingStrategy {

    /** Money scale used when rounding prices into domain signals. */
    protected final int moneyScale;

    protected AbstractTradingStrategy(int moneyScale) {
        this.moneyScale = moneyScale;
    }

    /**
     * Builds an entry signal for the last closed bar of {@code series}.
     *
     * @param filter          external filter; weight < 1.0 downgrades, 0.0 vetoes
     * @param indicatorValues key→value snapshot to persist with the signal
     */
    protected Signal newEntrySignal(BarSeries series, SignalFilter filter,
                                    Map<String, Object> indicatorValues) {
        Instant ts = SeriesFactory.lastEndTime(series);
        BigDecimal price = lastPrice(series);
        double weight = filter.weight(series.getName(), Direction.LONG);

        SignalVerdict verdict;
        String reason = null;
        if (weight <= 0.0) {
            verdict = SignalVerdict.FILTERED;
            reason = "SignalFilter vetoed (weight<=0)";
        } else if (weight < 1.0) {
            verdict = SignalVerdict.ACCEPTED;
            reason = "down-weighted by SignalFilter=" + weight;
        } else {
            verdict = SignalVerdict.ACCEPTED;
        }

        return Signal.builder()
                .id(UUID.randomUUID())
                .createdAt(Instant.now())
                .strategyId(id())
                .figi(series.getName())
                .ts(ts)
                .direction(Direction.LONG)
                .price(price)
                .indicatorSnapshot(indicatorValues)
                .verdict(verdict)
                .reason(reason)
                .build();
    }

    /** Close price of the last bar as domain BigDecimal, or null when empty. */
    protected BigDecimal lastPrice(BarSeries series) {
        if (series.isEmpty()) {
            return null;
        }
        return round(series.getLastBar().getClosePrice().doubleValue());
    }

    /** Rounds a raw price to the configured money scale. */
    protected BigDecimal round(double v) {
        return BigDecimal.valueOf(v).setScale(moneyScale, RoundingMode.HALF_UP);
    }

    /** Records a value into a snapshot map, skipping non-finite doubles. */
    protected void put(Map<String, Object> map, String key, Indicator<Num> ind, int index) {
        double v = SeriesFactory.last(ind);
        map.put(key, Double.isFinite(v) ? v : null);
    }

    protected Map<String, Object> newSnapshot() {
        return new LinkedHashMap<>();
    }
}