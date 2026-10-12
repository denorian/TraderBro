package com.traderbro.core.indicators;

import org.ta4j.core.BarSeries;

/**
 * Factory for the Bill Williams indicator family, created per {@link BarSeries} (these are pure
 * ta4j objects, never Spring beans). Keeps period/shift parameters in one place so the strategy,
 * the snapshot service and the UI all build consistent indicators from the same configuration.
 *
 * <p>Plain parameter records keep {@code core.indicators} free of Spring dependencies (see the
 * ArchUnit rule); wiring from {@code IndicatorsProperties} happens in the composition root.
 */
public final class IndicatorFactory {

    /** Alligator line periods, forward shifts, ATR window and entanglement threshold. */
    public record AlligatorParams(
            int jawPeriod, int jawShift,
            int teethPeriod, int teethShift,
            int lipsPeriod, int lipsShift,
            int atrPeriod, double entanglementAtrThreshold) {

        public static AlligatorParams williamsDefaults() {
            return new AlligatorParams(13, 8, 8, 5, 5, 3, 14, 0.5);
        }
    }

    /** Awesome Oscillator SMA windows. */
    public record AoParams(int fast, int slow) {

        public static AoParams williamsDefaults() {
            return new AoParams(5, 34);
        }
    }

    private final AlligatorParams alligator;
    private final AoParams ao;

    public IndicatorFactory(AlligatorParams alligator, AoParams ao) {
        this.alligator = alligator;
        this.ao = ao;
    }

    public static IndicatorFactory withDefaults() {
        return new IndicatorFactory(AlligatorParams.williamsDefaults(), AoParams.williamsDefaults());
    }

    public MedianPriceIndicator medianPrice(BarSeries series) {
        return new MedianPriceIndicator(series);
    }

    public AwesomeOscillatorIndicator awesomeOscillator(BarSeries series) {
        return new AwesomeOscillatorIndicator(series, ao.fast(), ao.slow());
    }

    /** AO with explicit windows (used when a strategy overrides the defaults). */
    public AwesomeOscillatorIndicator awesomeOscillator(BarSeries series, int fast, int slow) {
        return new AwesomeOscillatorIndicator(series, fast, slow);
    }

    public AlligatorIndicator alligator(BarSeries series) {
        return new AlligatorIndicator(series,
                alligator.jawPeriod(), alligator.jawShift(),
                alligator.teethPeriod(), alligator.teethShift(),
                alligator.lipsPeriod(), alligator.lipsShift(),
                alligator.atrPeriod(), alligator.entanglementAtrThreshold());
    }

    public FractalIndicator fractal(BarSeries series, FractalIndicator.Type type, int lookback) {
        return new FractalIndicator(series, type, lookback);
    }

    public AlligatorParams getAlligator() {
        return alligator;
    }

    public AoParams getAo() {
        return ao;
    }
}