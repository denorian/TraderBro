package com.traderbro.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parameters for the Bill Williams event detectors ({@code williams.*}): the AO "Twin Peaks"
 * divergence signal and the price-angulation (angle to the blue Jaw line) detector. All values
 * are normalised where noted so thresholds are portable across instruments with different
 * volatility.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "williams")
public class WilliamsProperties {

    private Divergence divergence = new Divergence();
    private Angulation angulation = new Angulation();

    /** "Twin Peaks" divergence signal on the Awesome Oscillator histogram. */
    @Getter
    @Setter
    public static class Divergence {
        /** Bars to the right that must be below an AO extremum before it is confirmed. */
        private int aoPeakWindow = 2;
        /** Minimum distance between the two peaks (bars). */
        private int minPeakDistance = 5;
        /** Maximum distance between the two peaks (bars). */
        private int maxPeakDistance = 60;
        /** Minimum amplitude of the first peak, in ATR(14) price equivalents. */
        private double minPeakAbsAtr = 0.2;
        /** Bars after the second peak within which the formation is confirmed or expires. */
        private int confirmBars = 10;
        /** ATR period used to normalise AO amplitude. */
        private int atrPeriod = 14;
    }

    /** Price angulation away from the blue Jaw (Alligator) line. */
    @Getter
    @Setter
    public static class Angulation {
        /** Max bars to look back for the last Jaw touch (the angulation anchor). */
        private int lookbackBars = 60;
        /** Minimum |priceSlope - jawSlope| in ATR/bars to call it angulation. */
        private double thresholdAtrPerBar = 0.1;
        /** How long an angulation event stays "fresh" for strategy filters (bars). */
        private int eventsLookbackBars = 30;
        /** ATR period used to normalise the slopes. */
        private int atrPeriod = 14;
    }
}