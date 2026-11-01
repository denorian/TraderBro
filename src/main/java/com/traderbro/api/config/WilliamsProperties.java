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
    private Aggregation aggregation = new Aggregation();

    /** Aggregation of repeated divergence/angulation signals into a combined strength. */
    @Getter
    @Setter
    public static class Aggregation {
        /** Events older than this many bars are excluded from the window. */
        private int windowBars = 120;
        /** Two events belong to the same "price block" when their peak prices differ by ≤ this %. */
        private double priceBlockPct = 1.0;
    }

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
        /** Minimum |priceSlope - jawSlope| in ATR/bars to call it angulation (legacy metric). */
        private double thresholdAtrPerBar = 0.1;
        /** Minimum geometric angle between price and Jaw in degrees (default 45°; stronger near
         * 90°). Primary gate — maps to the practitioner's ">45°, closer to 90°" semantics. */
        private double thresholdDeg = 45.0;
        /** How long an angulation event stays "fresh" for strategy filters (bars). */
        private int eventsLookbackBars = 30;
        /** ATR period used to normalise the slopes. */
        private int atrPeriod = 14;
    }
}