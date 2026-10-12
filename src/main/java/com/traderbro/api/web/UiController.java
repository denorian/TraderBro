package com.traderbro.api.web;

import com.traderbro.api.config.StreamHealth;
import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.IndicatorSnapshot;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.domain.spi.BarStore;
import com.traderbro.core.indicators.AlligatorIndicator;
import com.traderbro.core.indicators.AwesomeOscillatorIndicator;
import com.traderbro.core.indicators.IndicatorFactory;
import com.traderbro.core.indicators.SeriesFactory;
import com.traderbro.storage.IndicatorSnapshotRepository;
import com.traderbro.storage.InstrumentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.ta4j.core.BarSeries;
import org.ta4j.core.num.DoubleNum;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only REST API for the monitoring web UI. All endpoints are guarded by the shared
 * {@code X-Api-Key} (see {@link com.traderbro.api.security.ApiKeyFilter}).
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UiController {

    private static final int DEFAULT_CHART_BARS = 500;

    private final InstrumentRepository instruments;
    private final BarStore barStore;
    private final IndicatorSnapshotRepository snapshotRepository;
    private final IndicatorFactory indicatorFactory;
    private final StreamHealth streamHealth;

    // ------------------------------------------------------------------ /api/instruments
    public record InstrumentDto(String figi, String ticker, String name, String type,
                                String currency, int lot, boolean tradable, boolean streamUp) {
    }

    @GetMapping("/instruments")
    public List<InstrumentDto> instruments() {
        boolean connected = streamHealth.isConnected();
        return instruments.findAllTradable().stream()
                .map(i -> new InstrumentDto(i.getFigi(), i.getTicker(), i.getName(),
                        i.getInstrumentType().name(), i.getCurrency(), i.getLot(),
                        i.isTradable(), connected))
                .toList();
    }

    // ------------------------------------------------------------------ /api/chart/{figi}
    public record BarDto(Instant ts, BigDecimal open, BigDecimal high, BigDecimal low,
                         BigDecimal close, long volume) {
    }

    public record AlligatorPointDto(Instant ts, Double jaw, Double teeth, Double lips) {
    }

    public record AoPointDto(Instant ts, Double ao, Boolean rising) {
    }

    public record ChartResponse(String figi, String interval, List<BarDto> bars,
                                List<AlligatorPointDto> alligator, List<AoPointDto> ao) {
    }

    /**
     * Candles plus the Alligator (3 lines) and Awesome Oscillator for one instrument/timeframe.
     * Lines are computed deterministically from the bars so the chart is always correct even
     * before the snapshot catch-up has populated {@code indicator_snapshots}.
     */
    @GetMapping("/chart/{figi}")
    public ChartResponse chart(@PathVariable String figi,
                               @RequestParam(defaultValue = "1h") String interval,
                               @RequestParam(required = false) Instant from,
                               @RequestParam(required = false) Instant to,
                               @RequestParam(defaultValue = "500") int limit) {
        CandleInterval tf = parseInterval(interval);
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(
                SeriesFactory.toDuration(tf).multipliedBy(Math.max(limit, DEFAULT_CHART_BARS)));

        List<Bar> bars = barStore.findByFigiAndInterval(figi, tf, start, end);
        if (bars.size() > limit) {
            bars = bars.subList(bars.size() - limit, bars.size());
        }
        BarSeries series = SeriesFactory.fromDomain(bars, DoubleNum::valueOf, DEFAULT_CHART_BARS, figi);
        AlligatorIndicator alligator = indicatorFactory.alligator(series);
        AwesomeOscillatorIndicator ao = indicatorFactory.awesomeOscillator(series);

        List<BarDto> barDtos = new java.util.ArrayList<>();
        List<AlligatorPointDto> alligatorDtos = new java.util.ArrayList<>();
        List<AoPointDto> aoDtos = new java.util.ArrayList<>();
        for (int i = 0; i < series.getBarCount(); i++) {
            Instant ts = series.getBar(i).getEndTime();
            barDtos.add(new BarDto(ts,
                    BigDecimal.valueOf(series.getBar(i).getOpenPrice().doubleValue()),
                    BigDecimal.valueOf(series.getBar(i).getHighPrice().doubleValue()),
                    BigDecimal.valueOf(series.getBar(i).getLowPrice().doubleValue()),
                    BigDecimal.valueOf(series.getBar(i).getClosePrice().doubleValue()),
                    series.getBar(i).getVolume().longValue()));
            alligatorDtos.add(new AlligatorPointDto(ts, num(alligator.jaw(i)),
                    num(alligator.teeth(i)), num(alligator.lips(i))));
            aoDtos.add(new AoPointDto(ts, num(ao.getValue(i)), ao.isRising(i)));
        }
        return new ChartResponse(figi, tf.name(), barDtos, alligatorDtos, aoDtos);
    }

    // ------------------------------------------------------------------ /api/indicators/latest
    public record LatestIndicatorDto(String figi, String interval, Instant ts,
                                     Map<String, Object> alligator, Map<String, Object> ao) {
    }

    /** Latest Alligator + AO snapshot per instrument for the dashboard summary table. */
    @GetMapping("/indicators/latest")
    public List<LatestIndicatorDto> latestIndicators(
            @RequestParam(defaultValue = "1h") String interval) {
        CandleInterval tf = parseInterval(interval);
        Map<String, List<IndicatorSnapshot>> byFigi = snapshotRepository.latestForAll(tf);
        List<LatestIndicatorDto> out = new java.util.ArrayList<>();
        for (Map.Entry<String, List<IndicatorSnapshot>> e : byFigi.entrySet()) {
            Map<String, Object> alligator = Map.of();
            Map<String, Object> ao = Map.of();
            Instant ts = null;
            for (IndicatorSnapshot s : e.getValue()) {
                if (s.getIndicator() == IndicatorSnapshot.IndicatorKind.ALLIGATOR) {
                    alligator = s.getValues();
                    ts = s.getTs();
                } else if (s.getIndicator() == IndicatorSnapshot.IndicatorKind.AO) {
                    ao = s.getValues();
                }
            }
            out.add(new LatestIndicatorDto(e.getKey(), tf.name(), ts, alligator, ao));
        }
        out.sort(java.util.Comparator.comparing(LatestIndicatorDto::figi));
        return out;
    }

    private static CandleInterval parseInterval(String raw) {
        if (raw == null) {
            return CandleInterval.ONE_HOUR;
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "15M", "FIFTEEN_MIN" -> CandleInterval.FIFTEEN_MIN;
            case "1H", "ONE_HOUR" -> CandleInterval.ONE_HOUR;
            case "1D", "ONE_DAY" -> CandleInterval.ONE_DAY;
            case "1M", "ONE_MIN" -> CandleInterval.ONE_MIN;
            case "5M", "FIVE_MIN" -> CandleInterval.FIVE_MIN;
            default -> throw new IllegalArgumentException("unsupported interval: " + raw);
        };
    }

    private static Double num(org.ta4j.core.num.Num v) {
        return v == null ? null : v.doubleValue();
    }
}