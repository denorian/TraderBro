package com.traderbro.core.indicators;

import com.traderbro.api.config.IndicatorsProperties;
import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.IndicatorSnapshot;
import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.domain.spi.BarStore;
import com.traderbro.storage.IndicatorSnapshotRepository;
import com.traderbro.storage.InstrumentRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.num.DoubleNum;

/**
 * Computes per-bar indicator snapshots (Williams Alligator + Awesome Oscillator) across every
 * tradable instrument and working timeframe, and persists them idempotently into
 * {@code indicator_snapshots}. Snapshots are read-only reference data for the monitoring UI;
 * live decisions never read them back (see {@code AlligatorAoStrategy}).
 *
 * <p>Two triggers feed this service:
 * <ul>
 *   <li>{@link #onClosedBar(Bar)} — invoked by the candle stream on every closed bar;</li>
 *   <li>{@link #catchUp()} — gap-filling backfill over the retention window, run at startup and
 *       periodically, so missed bars (e.g. higher timeframes not delivered by the 15&nbsp;m
 *       stream) still get snapshots.</li>
 * </ul>
 * {@link #purgeExpired()} enforces {@code indicators.snapshot-retention-days}.
 */
@Slf4j
public class IndicatorSnapshotService {

    private final IndicatorFactory factory;
    private final BarStore barStore;
    private final InstrumentRepository instruments;
    private final IndicatorSnapshotRepository snapshots;
    private final AngulationDetector angulationDetector;
    private final com.traderbro.core.williams.WilliamsEventDetector williams;
    private final int retentionDays;
    private final List<CandleInterval> timeframes;
    private final int maxBars;
    private final int recentCatchupHours;

    public IndicatorSnapshotService(IndicatorFactory factory, BarStore barStore,
                                    InstrumentRepository instruments,
                                    IndicatorSnapshotRepository snapshots,
                                    AngulationDetector angulationDetector,
                                    com.traderbro.core.williams.WilliamsEventDetector williams,
                                    IndicatorsProperties props, int maxBars) {
        this.factory = factory;
        this.barStore = barStore;
        this.instruments = instruments;
        this.snapshots = snapshots;
        this.angulationDetector = angulationDetector;
        this.williams = williams;
        this.retentionDays = props.getSnapshotRetentionDays();
        this.timeframes = props.getSnapshotTimeframes().stream()
                .map(CandleInterval::valueOf)
                .toList();
        this.maxBars = maxBars;
        this.recentCatchupHours = props.getRecentCatchupHours();
    }

    /** Called by the stream when a candle closes: snapshot that instrument's timeframe. */
    public void onClosedBar(Bar bar) {
        if (bar == null || !timeframes.contains(bar.getInterval())) {
            return;
        }
        Instant from = bar.getTs().minus(
                SeriesFactory.toDuration(bar.getInterval()).multipliedBy(maxBars));
        computeRange(bar.getFigi(), bar.getInterval(), from, bar.getTs());
    }

    /** Gap-filling backfill over the retention window for every instrument and timeframe. */
    public void catchUp() {
        catchUpFrom(Instant.now().minus(retentionDays, ChronoUnit.DAYS), Instant.now());
    }

    /** Periodic pass over a recent window only — startup already backfills the full retention. */
    public void catchUpRecent() {
        catchUpFrom(Instant.now().minus(recentCatchupHours, ChronoUnit.HOURS), Instant.now());
    }

    private void catchUpFrom(Instant from, Instant to) {
        List<Instrument> all = instruments.findAllTradable();
        for (Instrument inst : all) {
            for (CandleInterval tf : timeframes) {
                try {
                    computeRange(inst.getFigi(), tf, from, to);
                } catch (RuntimeException e) {
                    log.error("snapshot catch-up failed for {} {}: {}", inst.getFigi(), tf,
                            e.getMessage());
                }
            }
        }
        log.info("IndicatorSnapshotService: catch-up pass finished over {} instruments",
                all.size());
    }

    /** Loads bars in {@code [from, to]}, computes snapshots for bars not yet snapshotted. */
    private void computeRange(String figi, CandleInterval tf, Instant from, Instant to) {
        List<Bar> bars = barStore.findByFigiAndInterval(figi, tf, from, to);
        if (bars.isEmpty()) {
            return;
        }
        Set<Instant> existing = snapshots.findSnapshotTs(figi, tf, from, to);
        List<IndicatorSnapshot> missing = computeMissing(figi, tf, bars, existing);
        if (!missing.isEmpty()) {
            snapshots.upsertAll(missing);
            log.debug("snapshots computed for {} {} : {} rows", figi, tf, missing.size());
        }
        // Event detectors (AO Twin Peaks divergence + angulation) run on the same closed-bar window.
        williams.detect(figi, tf, bars);
    }

    private List<IndicatorSnapshot> computeMissing(String figi, CandleInterval tf,
                                                   List<Bar> bars, Set<Instant> existing) {
        BarSeries series = SeriesFactory.fromDomain(bars, DoubleNum::valueOf, maxBars, figi);
        AlligatorIndicator alligator = factory.alligator(series);
        AwesomeOscillatorIndicator ao = factory.awesomeOscillator(series);
        ATRIndicator atr = alligator.getAtr();
        List<IndicatorSnapshot> out = new java.util.ArrayList<>();
        for (int i = 0; i < series.getBarCount(); i++) {
            Bar bar = bars.get(i);
            if (!bar.isFinal_() || existing.contains(bar.getTs())) {
                continue;
            }
            out.add(alligatorSnapshot(figi, tf, bar, alligator, i));
            out.add(aoSnapshot(figi, tf, bar, ao, i));
            out.add(angulationSnapshot(figi, tf, bar, alligator, atr, i));
        }
        return out;
    }

    private IndicatorSnapshot alligatorSnapshot(String figi, CandleInterval tf, Bar bar,
                                                AlligatorIndicator alligator, int i) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("jaw", num(alligator.jaw(i)));
        values.put("teeth", num(alligator.teeth(i)));
        values.put("lips", num(alligator.lips(i)));
        values.put("jaw_raw", num(alligator.jawRaw(i)));
        values.put("teeth_raw", num(alligator.teethRaw(i)));
        values.put("lips_raw", num(alligator.lipsRaw(i)));
        values.put("state", alligator.state(i).name());
        values.put("fan_width", round2(alligator.fanWidth(i)));
        return snapshot(figi, tf, bar, IndicatorSnapshot.IndicatorKind.ALLIGATOR, values);
    }

    private IndicatorSnapshot aoSnapshot(String figi, CandleInterval tf, Bar bar,
                                         AwesomeOscillatorIndicator ao, int i) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("ao", num(ao.getValue(i)));
        values.put("ao_rising", ao.isRising(i));
        values.put("bullish_saucer", ao.isBullishSaucer(i));
        values.put("bearish_saucer", ao.isBearishSaucer(i));
        values.put("above_zero", ao.isAboveZero(i));
        values.put("below_zero", ao.isBelowZero(i));
        values.put("zero_cross_up", i >= 1 && !ao.isAboveZero(i - 1) && ao.isAboveZero(i));
        values.put("zero_cross_down", i >= 1 && ao.isAboveZero(i - 1) && !ao.isAboveZero(i));
        return snapshot(figi, tf, bar, IndicatorSnapshot.IndicatorKind.AO, values);
    }

    private IndicatorSnapshot angulationSnapshot(String figi, CandleInterval tf, Bar bar,
                                             AlligatorIndicator alligator, ATRIndicator atr,
                                             int i) {
        Map<String, Object> values = new LinkedHashMap<>();
        angulationDetector.angleAt(barSeriesOf(alligator), alligator, atr, i, tf).ifPresent(a -> {
            values.put("angle_atr", round2(a.angleAtr()));
            values.put("direction", a.direction().name());
        });
        return snapshot(figi, tf, bar, IndicatorSnapshot.IndicatorKind.ANGULATION, values);
    }

    private static BarSeries barSeriesOf(AlligatorIndicator alligator) {
        return alligator.getBarSeries();
    }

    private IndicatorSnapshot snapshot(String figi, CandleInterval tf, Bar bar,
                                       IndicatorSnapshot.IndicatorKind kind,
                                       Map<String, Object> values) {
        return IndicatorSnapshot.builder()
                .figi(figi)
                .interval(tf)
                .ts(bar.getTs())
                .indicator(kind)
                .values(values)
                .build();
    }

    /** Deletes snapshots older than the retention window. */
    public int purgeExpired() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = snapshots.purgeOlderThan(cutoff);
        if (deleted > 0) {
            log.info("purged {} expired indicator snapshots (retention {}d)", deleted, retentionDays);
        }
        return deleted;
    }

    /** Periodic gap-filling pass over the recent window (higher timeframes, missed bars). */
    @Scheduled(fixedDelayString = "${indicators.catch-up-ms:300000}")
    public void scheduledCatchUp() {
        catchUpRecent();
    }

    /** Daily retention cleanup. */
    @Scheduled(cron = "${indicators.retention-cron:0 15 3 * * *}")
    public void scheduledPurge() {
        purgeExpired();
    }

    public Duration workingDuration(CandleInterval tf) {
        return SeriesFactory.toDuration(tf);
    }

    private static Double num(org.ta4j.core.num.Num v) {
        return v == null ? null : v.doubleValue();
    }

    private static double round2(double v) {
        return Double.isFinite(v) ? Math.round(v * 100.0) / 100.0 : 0.0;
    }
}