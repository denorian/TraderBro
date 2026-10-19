package com.traderbro.core.domain;

import com.traderbro.core.domain.enums.CandleInterval;
import java.time.Instant;
import java.util.Map;
import lombok.Builder;
import lombok.Value;

/**
 * A per-bar indicator snapshot persisted for the monitoring UI and analytics.
 * One row per (figi, interval, ts, indicator). Values are the indicator's derived
 * features as a JSON map (e.g. the three Alligator lines + state + fan width, or AO +
 * its boolean flags).
 *
 * <p>Snapshots are <em>read-only</em> reference data for the dashboard and chart: live
 * decisions never read them back, they are computed on the fly from bars (see
 * {@code AlligatorAoStrategy}). This keeps the trading path free of look-ahead risk.
 */
@Value
@Builder
public class IndicatorSnapshot {

    String figi;
    CandleInterval interval;
    Instant ts;
    IndicatorKind indicator;
    Map<String, Object> values;

    /** Which Williams indicator the snapshot carries. */
    public enum IndicatorKind {
        ALLIGATOR,
        AO,
        ANGULATION
    }
}