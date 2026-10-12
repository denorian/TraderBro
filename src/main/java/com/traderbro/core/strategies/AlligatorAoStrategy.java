package com.traderbro.core.strategies;

import com.traderbro.core.domain.Signal;
import com.traderbro.core.domain.enums.Direction;
import com.traderbro.core.domain.spi.SignalFilter;
import com.traderbro.core.indicators.AlligatorIndicator;
import com.traderbro.core.indicators.AlligatorIndicator.AlligatorState;
import com.traderbro.core.indicators.AwesomeOscillatorIndicator;
import com.traderbro.core.indicators.FractalIndicator;
import com.traderbro.core.indicators.IndicatorFactory;
import com.traderbro.core.indicators.SeriesFactory;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntPredicate;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Rule;
import org.ta4j.core.Strategy;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.rules.CrossedUpIndicatorRule;

/**
 * Fifth strategy, following Bill Williams' Alligator + Awesome Oscillator system.
 *
 * <p><b>Entry regime filter</b> — the Alligator must be out of SLEEPING:
 * <ul>
 *   <li>LONG allowed when {@code state ∈ {AWAKENING, EATING_BULL}};</li>
 *   <li>SHORT allowed when {@code state ∈ {AWAKENING, EATING_BEAR}} — futures only, gated by the
 *       {@code allowShort} parameter and enforced for shares in the signal engine;</li>
 * </ul>
 * <b>Entry trigger</b> — AO crosses the zero line in the trend direction <em>or</em> a matching
 * saucer forms while the fan is already open.
 *
 * <p><b>Exit</b> (encoded in {@link #build} for the backtester): close crossing behind the Teeth
 * line, a reversal saucer on the AO, or a stop behind the trailing {@code stopLookback}-bar
 * extremum ({@link FractalIndicator}, see its no-look-ahead rationale).
 *
 * <p>All live decisions use only data {@code <=} the current bar (no look-ahead): the strategy
 * reads the Alligator's <em>current</em> (unshifted) lines, never the forward-shifted plot lines.
 */
public class AlligatorAoStrategy extends AbstractTradingStrategy {

    public static final String ID = "AlligatorAoStrategy";
    public static final String DEFAULT_FAST = "5";
    public static final String DEFAULT_SLOW = "34";

    private final IndicatorFactory.AlligatorParams alligator;

    public AlligatorAoStrategy(int moneyScale, IndicatorFactory.AlligatorParams alligator) {
        super(moneyScale);
        this.alligator = alligator;
    }

    @Override
    public String id() {
        return ID;
    }

    /** Strategy-scoped parameters with defaults. */
    private record Params(int fast, int slow, double entanglementAtrThreshold,
                          int stopLookback, boolean allowShort, int atrPeriod) {
    }

    private Params params(StrategyParams p) {
        return new Params(
                p.getInt("fast", 5),
                p.getInt("slow", 34),
                p.getDouble("entanglementAtrThreshold", 0.5),
                p.getInt("stopLookback", 5),
                p.getBoolean("allowShort", true),
                p.getInt("atrPeriod", 14));
    }

    private AlligatorIndicator alligator(BarSeries series, Params p) {
        return new AlligatorIndicator(series,
                alligator.jawPeriod(), alligator.jawShift(),
                alligator.teethPeriod(), alligator.teethShift(),
                alligator.lipsPeriod(), alligator.lipsShift(),
                p.atrPeriod(), p.entanglementAtrThreshold());
    }

    @Override
    public Strategy build(BarSeries series, StrategyParams params) {
        Params p = params(params);
        AlligatorIndicator all = alligator(series, p);
        AwesomeOscillatorIndicator ao = new AwesomeOscillatorIndicator(series, p.fast(), p.slow());
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        FractalIndicator fractalLow = new FractalIndicator(series, FractalIndicator.Type.LOW, p.stopLookback());

        Rule stateBullish = stateRule(all, AlligatorState.AWAKENING, AlligatorState.EATING_BULL);
        Rule aoCrossUp = new CrossedUpIndicatorRule(ao, series.numFactory().zero());
        Rule bullSaucer = rule(ao::isBullishSaucer);
        Rule entry = stateBullish.and(aoCrossUp.or(bullSaucer));

        Rule closeUnderTeeth = rule(idx -> all.isReady(idx)
                && close.getValue(idx).isLessThan(all.teethRaw(idx)));
        Rule reversalSaucer = rule(ao::isBearishSaucer);
        Rule stopLong = rule(idx -> all.isReady(idx)
                && close.getValue(idx).isLessThan(fractalLow.getValue(idx)));
        Rule exit = closeUnderTeeth.or(reversalSaucer).or(stopLong);

        return new BaseStrategy(ID, entry, exit);
    }

    @Override
    public Optional<Signal> evaluate(BarSeries series, StrategyParams params, SignalFilter filter) {
        int last = series.getEndIndex();
        if (last < 1) {
            return Optional.empty();
        }
        Params p = params(params);
        AlligatorIndicator all = alligator(series, p);
        AwesomeOscillatorIndicator ao = new AwesomeOscillatorIndicator(series, p.fast(), p.slow());
        if (!all.isReady(last) || !SeriesFactory.isPresent(ao, last)) {
            return Optional.empty();
        }
        AlligatorState state = all.state(last);
        Direction direction = null;

        boolean bullState = state == AlligatorState.AWAKENING || state == AlligatorState.EATING_BULL;
        boolean bullTrigger = zeroCrossUp(ao, last) || ao.isBullishSaucer(last);
        if (bullState && bullTrigger) {
            direction = Direction.LONG;
        } else if (p.allowShort()) {
            boolean bearState = state == AlligatorState.AWAKENING || state == AlligatorState.EATING_BEAR;
            boolean bearTrigger = zeroCrossDown(ao, last) || ao.isBearishSaucer(last);
            if (bearState && bearTrigger) {
                direction = Direction.SHORT;
            }
        }
        if (direction == null) {
            return Optional.empty();
        }

        Map<String, Object> snap = newSnapshot();
        snap.put("state", state.name());
        snap.put("ao", SeriesFactory.last(ao));
        snap.put("ao_rising", ao.isRising(last));
        snap.put("bullish_saucer", ao.isBullishSaucer(last));
        snap.put("bearish_saucer", ao.isBearishSaucer(last));
        snap.put("jaw", num(all.jawRaw(last)));
        snap.put("teeth", num(all.teethRaw(last)));
        snap.put("lips", num(all.lipsRaw(last)));
        snap.put("fan_width", all.fanWidth(last));
        return Optional.of(newEntrySignal(series, direction, filter, snap));
    }

    private static Double num(org.ta4j.core.num.Num v) {
        return v == null ? null : v.doubleValue();
    }

    private static boolean zeroCrossUp(AwesomeOscillatorIndicator ao, int i) {
        return i >= 1 && !ao.isAboveZero(i - 1) && ao.isAboveZero(i);
    }

    private static boolean zeroCrossDown(AwesomeOscillatorIndicator ao, int i) {
        return i >= 1 && ao.isAboveZero(i - 1) && !ao.isAboveZero(i);
    }

    /** A ta4j rule satisfied when the Alligator phase is one of {@code states}. */
    private static Rule stateRule(AlligatorIndicator all, AlligatorState... states) {
        EnumSet<AlligatorState> set = EnumSet.of(states[0], states);
        return new BoolRule(idx -> set.contains(all.state(idx)), "alligatorState");
    }

    /** Wraps a single-index predicate as a {@link Rule}. */
    private static Rule rule(IntPredicate predicate) {
        return new BoolRule(predicate, "rule");
    }

    /** Minimal {@link Rule} backed by an index predicate (no position tracking needed). */
    private static final class BoolRule implements Rule {
        private final IntPredicate predicate;
        private final String name;

        BoolRule(IntPredicate predicate, String name) {
            this.predicate = predicate;
            this.name = name;
        }

        @Override
        public boolean isSatisfied(int index, TradingRecord record) {
            return predicate.test(index);
        }

        @Override
        public boolean isSatisfied(int index) {
            return predicate.test(index);
        }

        @Override
        public String getName() {
            return name;
        }
    }
}