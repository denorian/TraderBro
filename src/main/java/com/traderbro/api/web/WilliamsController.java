package com.traderbro.api.web;

import com.traderbro.core.domain.AngulationEvent;
import com.traderbro.core.domain.DivergenceEvent;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.storage.AngulationEventRepository;
import com.traderbro.storage.DivergenceEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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