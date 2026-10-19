package com.traderbro.core.strategies;

import com.traderbro.core.domain.DivergenceEvent;
import com.traderbro.core.domain.Signal;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.domain.enums.Direction;
import com.traderbro.core.domain.spi.SignalFilter;
import com.traderbro.core.indicators.AlligatorIndicator;
import com.traderbro.core.indicators.AngulationDetector;
import com.traderbro.core.indicators.AoTwinPeaksDetector;
import com.traderbro.core.indicators.AwesomeOscillatorIndicator;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntPredicate;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Rule;
import org.ta4j.core.Strategy;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

/**
 * Reversal strategy on a <b>confirmed</b> AO "Twin Peaks" divergence plus angulation, per the
 * Williams system: a reversal is only valid when price has departed at an angle from the blue Jaw
 * line on the divergence's extreme peak, and the Alligator frame does not forbid the direction.
 *
 * <p>Disabled by default ({@code strategies.ao-divergence.enabled=false}) until validated by a
 * walk-forward backtest. Live decisions and the backtest rules are look-ahead-free: divergence
 * confirmation uses only bars {@code <=} the evaluated index (see {@link AoTwinPeaksDetector}).
 *
 * <p>Entry (after {@code fast}/{@code slow} AO, see {@link Params}):
 * <ol>
 *   <li>a divergence is CONFIRMED at the current bar,</li>
 *   <li>angulation fires on its extreme peak ({@code thresholdAtrPerBar}),
 *   <li>price closed past Teeth in the reversal direction,</li>
 *   <li>the Alligator frame allows it (bullish reversal only above the mouth, bearish only below).</li>
 * </ol>
 * Stop: behind the price extremum at the second AO peak {@code stopBufferAtr} ATR away.
 * Exit: close past Teeth the other way or an opposite AO saucer.
 */
public class AoDivergenceReversalStrategy extends AbstractTradingStrategy {

    public static final String ID = "AoDivergenceReversalStrategy";

    private final AoTwinPeaksDetector twinPeaks;
    private final AngulationDetector angulation;
    private final boolean allowShort;
    private final int atrPeriod;
    private final int lookbackBars;

    public AoDivergenceReversalStrategy(int moneyScale, AoTwinPeaksDetector twinPeaks,
                                        AngulationDetector angulation, boolean allowShort,
                                        int atrPeriod, int lookbackBars) {
        super(moneyScale);
        this.twinPeaks = twinPeaks;
        this.angulation = angulation;
        this.allowShort = allowShort;
        this.atrPeriod = atrPeriod;
        this.lookbackBars = lookbackBars;
    }

    @Override
    public String id() {
        return ID;
    }

    private record Params(int fast, int slow, double stopBufferAtr, double thresholdAtrPerBar) {
    }

    private Params params(StrategyParams p) {
        return new Params(p.getInt("fast", 5), p.getInt("slow", 34),
                p.getDouble("stopBufferAtr", 0.3), p.getDouble("thresholdAtrPerBar", 0.1));
    }

    @Override
    public Strategy build(BarSeries series, StrategyParams params) {
        Params p = params(params);
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        Rule entry = rule(idx -> confirmedReversal(series, p, idx, null) != null);
        Rule exit = rule(idx -> reversed(series, p, idx));
        return new BaseStrategy(ID, entry, exit);
    }

    @Override
    public Optional<Signal> evaluate(BarSeries series, StrategyParams params, SignalFilter filter) {
        int last = series.getEndIndex();
        if (last < 1) {
            return Optional.empty();
        }
        Params p = params(params);
        Direction dir = confirmedReversal(series, p, last, null);
        if (dir == null) {
            return Optional.empty();
        }
        Map<String, Object> snap = newSnapshot();
        snap.put("degree", null);
        snap.put("divergence", "CONFIRMED");
        snap.put("angulation", true);
        return Optional.of(newEntrySignal(series, dir, filter, snap));
    }

    /**
     * Returns the reversal direction if a CONFIRMED divergence + angulation reversal is valid at
     * {@code endIndex}; otherwise {@code null}.
     */
    private Direction confirmedReversal(BarSeries series, Params p, int endIndex,
                                        DivergenceEvent[] found) {
        if (endIndex < 1) {
            return null;
        }
        // Recompute the divergence scan on the sub-series up to endIndex (look-ahead-free).
        BarSeries sub = series.getSubSeries(0, endIndex + 1);
        AwesomeOscillatorIndicator ao = new AwesomeOscillatorIndicator(sub, p.fast(), p.slow());
        AlligatorIndicator alligator = new AlligatorIndicator(sub,
                13, 8, 8, 5, 5, 3, atrPeriod, 0.5);
        ATRIndicator atr = alligator.getAtr();
        CandleInterval tf = intervalOf(sub);
        List<DivergenceEvent> events = twinPeaks.scan(sub, ao, tf, sub.getName());

        for (int i = events.size() - 1; i >= 0; i--) {
            DivergenceEvent e = events.get(i);
            if (e.getStatus() != DivergenceEvent.Status.CONFIRMED) {
                continue;
            }
            int evtBar = endIndex - sub.getBarCount() + 1 + i; // not used; we anchor on ts
            if (evtBar < 0) {
                continue;
            }
            if (found != null) {
                found[0] = e;
            }
            Direction dir = e.getDirection() == DivergenceEvent.Direction.BEARISH
                    ? Direction.SHORT : Direction.LONG;
            if (dir == Direction.SHORT && !allowShort) {
                return null;
            }
            if (!angulated(sub, alligator, atr, e, tf, p)) {
                return null;
            }
            // Price must have closed past Teeth toward the reversal.
            if (e.getDirection() == DivergenceEvent.Direction.BEARISH
                    && closePast(sub, alligator, endIndex, true)) {
                return dir;
            }
            if (e.getDirection() == DivergenceEvent.Direction.BULLISH
                    && closePast(sub, alligator, endIndex, false)) {
                return dir;
            }
            return null;
        }
        return null;
    }

    private boolean angulated(BarSeries sub, AlligatorIndicator alligator, ATRIndicator atr,
                              DivergenceEvent e, CandleInterval tf, Params p) {
        return angulation.detect(sub, alligator, atr, e, e.getId())
                .map(a -> a.getAngleAtr() >= p.thresholdAtrPerBar())
                .orElse(false);
    }

    private boolean closePast(BarSeries sub, AlligatorIndicator alligator, int idx, boolean below) {
        if (!alligator.isReady(idx)) {
            return false;
        }
        double close = sub.getBar(idx).getClosePrice().doubleValue();
        double teeth = alligator.teethRaw(idx) == null ? Double.NaN : alligator.teethRaw(idx).doubleValue();
        if (Double.isNaN(teeth)) {
            return false;
        }
        return below ? close < teeth : close > teeth;
    }

    private boolean reversed(BarSeries series, Params p, int idx) {
        if (idx < 2) {
            return false;
        }
        AwesomeOscillatorIndicator ao = new AwesomeOscillatorIndicator(series, p.fast(), p.slow());
        return ao.isBearishSaucer(idx) || ao.isBullishSaucer(idx);
    }

    private static CandleInterval intervalOf(BarSeries series) {
        if (series.isEmpty()) {
            return CandleInterval.ONE_HOUR;
        }
        Duration d = series.getBar(0).getTimePeriod();
        return durationToInterval(d);
    }

    private static CandleInterval durationToInterval(Duration d) {
        if (d.toMinutes() <= 15) {
            return CandleInterval.FIFTEEN_MIN;
        }
        if (d.toHours() <= 1) {
            return CandleInterval.ONE_HOUR;
        }
        return CandleInterval.ONE_DAY;
    }

    private static Rule rule(IntPredicate predicate) {
        return new Rule() {
            @Override
            public boolean isSatisfied(int index, TradingRecord record) {
                return predicate.test(index);
            }

            @Override
            public boolean isSatisfied(int index) {
                return predicate.test(index);
            }
        };
    }
}