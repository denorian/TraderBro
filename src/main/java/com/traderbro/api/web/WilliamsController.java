package com.traderbro.api.web;

import com.traderbro.core.domain.AngulationEvent;
import com.traderbro.core.domain.DivergenceEvent;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.williams.SignalStrengthAggregator;
import com.traderbro.storage.AngulationEventRepository;
import com.traderbro.storage.DivergenceEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only REST API for the Bill Williams event detectors: AO "Twin Peaks" divergences and
 * price angulation events. Guarded by the shared {@code X-Api-Key}.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WilliamsController {

    private final DivergenceEventRepository divergences;
    private final AngulationEventRepository angulations;
    private final SignalStrengthAggregator aggregator;

    /** Paginated divergence events, filterable by instrument/timeframe/window/status. */
    @GetMapping("/divergences")
    public List<DivergenceEvent> divergences(
            @RequestParam(required = false) String figi,
            @RequestParam(required = false) String interval,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) DivergenceEvent.Status status,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return divergences.find(figi, parse(interval), from, to, status, limit, offset);
    }

    /** Latest divergence per instrument for a timeframe (Dashboard pins). */
    @GetMapping("/divergences/latest")
    public List<DivergenceEvent> latestDivergences(
            @RequestParam(defaultValue = "1h") String interval) {
        return divergences.latestForAll(parse(interval));
    }

    /** Angulation events, optionally filtered by instrument/timeframe. */
    @GetMapping("/angulation")
    public List<AngulationEvent> angulation(
            @RequestParam(required = false) String figi,
            @RequestParam(required = false) String interval,
            @RequestParam(defaultValue = "100") int limit) {
        return angulations.find(figi, parse(interval), limit);
    }

    /** Combined signal strength per (direction, price block) over the aggregation window. */
    @GetMapping("/williams/strength")
    public List<SignalStrengthAggregator.BlockStrength> strength(
            @RequestParam String figi,
            @RequestParam(defaultValue = "1h") String interval) {
        CandleInterval tf = parse(interval);
        if (tf == null) {
            return List.of();
        }
        Instant now = Instant.now();
        Instant cutoff = now.minus(Duration.ofMillis((long) aggregator.windowBars()
                * durationMillis(tf)));
        List<DivergenceEvent> divs = divergences.find(figi, tf, cutoff, now, null, 2000, 0);
        Map<UUID, AngulationEvent> ang = angulations.find(figi, tf, 2000).stream()
                .filter(a -> !a.getTs().isBefore(cutoff))
                .collect(Collectors.toMap(AngulationEvent::getDivergenceEventId,
                        a -> a, (x, y) -> x));
        return aggregator.aggregate(divs, ang);
    }

    private static long durationMillis(CandleInterval tf) {
        return switch (tf) {
            case ONE_MIN -> 60_000L;
            case FIVE_MIN -> 5 * 60_000L;
            case FIFTEEN_MIN -> 15 * 60_000L;
            case ONE_HOUR -> 3_600_000L;
            case FOUR_HOUR -> 4 * 3_600_000L;
            case ONE_DAY -> 86_400_000L;
        };
    }

    private static CandleInterval parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "15M", "FIFTEEN_MIN" -> CandleInterval.FIFTEEN_MIN;
            case "1H", "ONE_HOUR" -> CandleInterval.ONE_HOUR;
            case "4H", "FOUR_HOUR" -> CandleInterval.FOUR_HOUR;
            case "1D", "ONE_DAY" -> CandleInterval.ONE_DAY;
            case "1M", "ONE_MIN" -> CandleInterval.ONE_MIN;
            case "5M", "FIVE_MIN" -> CandleInterval.FIVE_MIN;
            default -> null;
        };
    }
}