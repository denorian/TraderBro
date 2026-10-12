package com.traderbro.api.config;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Williams-indicator and snapshot configuration ({@code indicators.*}).
 * The factory and snapshot service read these values; strategies read the strategy-specific
 * overrides from {@code strategy_config}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "indicators")
public class IndicatorsProperties {

    /** Alligator line periods, forward shifts and entanglement threshold. */
    private Alligator alligator = new Alligator();
    /** Awesome Oscillator SMA windows. */
    private AwesomeOscillator awesomeOscillator = new AwesomeOscillator();
    /** Days of {@code indicator_snapshots} history retained (purged by the scheduled job). */
    private int snapshotRetentionDays = 90;
    /** Timeframes the snapshot service computes across (all working intervals). */
    private List<String> snapshotTimeframes = List.of("FIFTEEN_MIN", "ONE_HOUR", "ONE_DAY");

    @Getter
    @Setter
    public static class Alligator {
        private int jawPeriod = 13;
        private int jawShift = 8;
        private int teethPeriod = 8;
        private int teethShift = 5;
        private int lipsPeriod = 5;
        private int lipsShift = 3;
        private int atrPeriod = 14;
        private double entanglementAtrThreshold = 0.5;
    }

    @Getter
    @Setter
    public static class AwesomeOscillator {
        private int fast = 5;
        private int slow = 34;
    }
}