package com.traderbro.core.williams;

import com.traderbro.core.domain.AngulationEvent;
import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.DivergenceEvent;
import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.event.NotificationLevel;
import com.traderbro.core.event.NotificationType;
import com.traderbro.core.event.TraderEvent;
import com.traderbro.core.event.TraderEventPublisher;
import com.traderbro.core.indicators.AlligatorIndicator;
import com.traderbro.core.indicators.AngulationDetector;
import com.traderbro.core.indicators.AoTwinPeaksDetector;
import com.traderbro.core.indicators.AwesomeOscillatorIndicator;
import com.traderbro.core.indicators.IndicatorFactory;
import com.traderbro.core.indicators.SeriesFactory;
import com.traderbro.storage.AngulationEventRepository;
import com.traderbro.storage.DivergenceEventRepository;
import com.traderbro.storage.InstrumentRepository;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.num.DoubleNum;

/**
 * Drives the Williams event detectors over a closed-bar window: runs the AO "Twin Peaks" scan and
 * the angulation detector, persists the resulting events idempotently, and publishes Telegram
 * notifications for new formations and confirmations. Invoked from {@code IndicatorSnapshotService}
 * on every closed bar and during catch-up, so it never reads future data.
 */
@Slf4j
public class WilliamsEventDetector {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm");
    private static final DecimalFormat D2 = new DecimalFormat("#,##0.##");

    private final IndicatorFactory factory;
    private final AoTwinPeaksDetector twinPeaks;
    private final AngulationDetector angulationDetector;
    private final DivergenceEventRepository divergences;
    private final AngulationEventRepository angulations;
    private final InstrumentRepository instruments;
    private final TraderEventPublisher publisher;
    private final int maxBars;

    public WilliamsEventDetector(IndicatorFactory factory, AoTwinPeaksDetector twinPeaks,
                                 AngulationDetector angulationDetector,
                                 DivergenceEventRepository divergences,
                                 AngulationEventRepository angulations,
                                 InstrumentRepository instruments,
                                 TraderEventPublisher publisher, int maxBars) {
        this.factory = factory;
        this.twinPeaks = twinPeaks;
        this.angulationDetector = angulationDetector;
        this.divergences = divergences;
        this.angulations = angulations;
        this.instruments = instruments;
        this.publisher = publisher;
        this.maxBars = maxBars;
    }

    /** Detects, persists and notifies Williams events for a closed-bar window. */
    public void detect(String figi, CandleInterval tf, List<Bar> bars) {
        if (bars.isEmpty()) {
            return;
        }
        BarSeries series = SeriesFactory.fromDomain(bars, DoubleNum::valueOf, maxBars, figi);
        AwesomeOscillatorIndicator ao = factory.awesomeOscillator(series);
        AlligatorIndicator alligator = factory.alligator(series);
        ATRIndicator atr = alligator.getAtr();

        Instant from = bars.get(0).getTs();
        Instant to = bars.get(bars.size() - 1).getTs();
        Map<UUID, DivergenceEvent.Status> known = existingStatus(figi, tf, from, to);
        Set<UUID> knownAng = angulations.find(figi, tf, 2000).stream()
                .map(AngulationEvent::getDivergenceEventId)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));

        List<DivergenceEvent> events = twinPeaks.scan(series, ao, tf, figi);
        List<AngulationEvent> angs = new ArrayList<>();
        for (DivergenceEvent div : events) {
            DivergenceEvent.Status prev = known.get(div.getId());
            boolean isNew = prev == null;
            divergences.upsertAll(List.of(div));

            if (isNew && div.getStatus() == DivergenceEvent.Status.UNCONFIRMED) {
                notifyDivergence(NotificationType.AO_DIVERGENCE_DETECTED,
                        NotificationLevel.INFO, div, null);
            }
            if (div.getStatus() == DivergenceEvent.Status.CONFIRMED
                    && (isNew || prev == DivergenceEvent.Status.UNCONFIRMED)) {
                notifyDivergence(NotificationType.AO_DIVERGENCE_CONFIRMED,
                        NotificationLevel.WARNING, div, null);
            }

            angulationDetector.detect(series, alligator, atr, div, div.getId())
                    .ifPresent(a -> {
                        angs.add(a);
                        if (!knownAng.contains(div.getId())) {
                            notifyDivergence(NotificationType.ANGULATION_DETECTED,
                                    NotificationLevel.INFO, div, a);
                        }
                    });
        }
        angulations.upsertAll(angs);
        if (!events.isEmpty()) {
            log.debug("williams: {} divergences, {} angulations for {} {}", events.size(),
                    angs.size(), figi, tf);
        }
    }

    private Map<UUID, DivergenceEvent.Status> existingStatus(String figi, CandleInterval tf,
                                                             Instant from, Instant to) {
        Map<UUID, DivergenceEvent.Status> out = new HashMap<>();
        for (DivergenceEvent e : divergences.find(figi, tf, from, to, null, 2000, 0)) {
            out.put(e.getId(), e.getStatus());
        }
        return out;
    }

    private void notifyDivergence(NotificationType type, NotificationLevel level,
                                  DivergenceEvent div, AngulationEvent ang) {
        Map<String, Object> p = new LinkedHashMap<>();
        boolean bearish = div.getDirection() == DivergenceEvent.Direction.BEARISH;
        p.put("ticker", ticker(div.getFigi()));
        p.put("interval", intervalLabel(div.getInterval()));
        p.put("direction", bearish ? "МЕДВЕЖЬЯ" : "БЫЧЬЯ");
        p.put("dirIcon", bearish ? "📉" : "📈");
        p.put("degree", div.getDegree());
        p.put("degreeLabel", div.getDegree() == 2 ? "двойная" : "одинарная");
        p.put("aoPeaks", peak(div.getAoPeak1Value(), div.getAoPeak1Ts()) + " → "
                + peak(div.getAoPeak2Value(), div.getAoPeak2Ts()));
        p.put("priceAtPeaks", D2.format(div.getPriceAtPeak1()) + " → "
                + D2.format(div.getPriceAtPeak2()));
        p.put("strength", D2.format(div.getStrength()));
        p.put("angulation", ang == null ? "нет"
                : "есть — " + D2.format(ang.getAngleAtr()) + " ATR/бар, уход "
                        + (ang.getDirection() == AngulationEvent.Direction.AWAY_UP ? "вверх" : "вниз"));
        if (ang != null) {
            p.put("priceSlope", D2.format(ang.getPriceSlopeAtr()));
            p.put("jawSlope", D2.format(ang.getJawSlopeAtr()));
            p.put("angleAtr", D2.format(ang.getAngleAtr()));
            p.put("bars", ang.getBarsInWindow());
        }
        publisher.publish(TraderEvent.of(type, level, p));
    }

    private String ticker(String figi) {
        return instruments.findByFigi(figi).map(Instrument::getTicker).orElse(figi);
    }

    private static String intervalLabel(CandleInterval tf) {
        return switch (tf) {
            case FIFTEEN_MIN -> "15м";
            case ONE_HOUR -> "1ч";
            case FOUR_HOUR -> "4ч";
            case ONE_DAY -> "1д";
            default -> tf.name();
        };
    }

    private static String peak(double value, Instant ts) {
        return D2.format(value) + " (" + HMS.withZone(MSK).format(ts) + ")";
    }
}