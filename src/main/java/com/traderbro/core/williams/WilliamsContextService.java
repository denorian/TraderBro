package com.traderbro.core.williams;

import com.traderbro.core.domain.AngulationEvent;
import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.DivergenceEvent;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.domain.spi.BarStore;
import com.traderbro.core.indicators.AngulationDetector;
import com.traderbro.core.indicators.AlligatorIndicator;
import com.traderbro.core.indicators.IndicatorFactory;
import com.traderbro.core.indicators.SeriesFactory;
import com.traderbro.storage.AngulationEventRepository;
import com.traderbro.storage.DivergenceEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.num.DoubleNum;

/**
 * Aggregates the Williams context for a (figi, interval): the latest divergence event, recent
 * angulation events and the current background angulation angle. Serves the monitoring REST
 * endpoints and can be used as a reversal-signal validity filter by strategies.
 */
public class WilliamsContextService {

    private final IndicatorFactory factory;
    private final AngulationDetector angulationDetector;
    private final DivergenceEventRepository divergences;
    private final AngulationEventRepository angulations;
    private final BarStore barStore;
    private final int maxBars;

    public WilliamsContextService(IndicatorFactory factory, AngulationDetector angulationDetector,
                                  DivergenceEventRepository divergences,
                                  AngulationEventRepository angulations,
                                  BarStore barStore, int maxBars) {
        this.factory = factory;
        this.angulationDetector = angulationDetector;
        this.divergences = divergences;
        this.angulations = angulations;
        this.barStore = barStore;
        this.maxBars = maxBars;
    }

    public DivergenceEvent latestDivergence(String figi, CandleInterval tf) {
        List<DivergenceEvent> list = divergences.findLatest(figi, tf, 1);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<DivergenceEvent> divergences(String figi, CandleInterval tf, int limit) {
        return divergences.findLatest(figi, tf, limit);
    }

    public List<AngulationEvent> angulation(String figi, CandleInterval tf, int limit) {
        return angulations.find(figi, tf, limit);
    }

    /** Current background angulation angle computed on the recent window. */
    public Optional<AngulationDetector.BackgroundAngle> backgroundAngle(String figi,
                                                                        CandleInterval tf) {
        Instant to = Instant.now();
        Instant from = to.minus(SeriesFactory.toDuration(tf).multipliedBy(maxBars));
        List<Bar> bars = barStore.findByFigiAndInterval(figi, tf, from, to);
        if (bars.isEmpty()) {
            return Optional.empty();
        }
        BarSeries series = SeriesFactory.fromDomain(bars, DoubleNum::valueOf, maxBars, figi);
        AlligatorIndicator alligator = factory.alligator(series);
        ATRIndicator atr = alligator.getAtr();
        return angulationDetector.backgroundAngle(series, alligator, atr, tf);
    }
}