package com.traderbro.scheduler;

import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.Order;
import com.traderbro.core.domain.OrderRequest;
import com.traderbro.core.domain.Portfolio;
import com.traderbro.core.domain.Signal;
import com.traderbro.core.domain.StrategyConfig;
import com.traderbro.core.domain.enums.OrderSide;
import com.traderbro.core.domain.enums.SignalVerdict;
import com.traderbro.core.domain.spi.BarStore;
import com.traderbro.core.domain.spi.PortfolioProvider;
import com.traderbro.core.event.NotificationLevel;
import com.traderbro.core.event.NotificationType;
import com.traderbro.core.event.TraderEvent;
import com.traderbro.core.event.TraderEventPublisher;
import com.traderbro.core.indicators.SeriesFactory;
import com.traderbro.core.strategies.PositionSizer;
import com.traderbro.core.strategies.StrategyParams;
import com.traderbro.core.strategies.StrategyRegistry;
import com.traderbro.core.strategies.TradingStrategy;
import com.traderbro.execution.order.OrderManager;
import com.traderbro.execution.state.StateRecovery;
import com.traderbro.storage.InstrumentRepository;
import com.traderbro.storage.SignalRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ta4j.core.BarSeries;
import org.ta4j.core.num.DoubleNum;

/**
 * Live signal engine: on every closed bar, evaluates each active strategy over each tradable
 * instrument at the strategy's interval, persists the signal, sizes the position and submits an
 * order through {@link OrderManager} (unless in observe-only mode).
 */
@Slf4j
@RequiredArgsConstructor
public class SignalEngine {

    private final StrategyRegistry registry;
    private final BarStore barStore;
    private final InstrumentRepository instrumentRepository;
    private final PositionSizer positionSizer;
    private final OrderManager orderManager;
    private final StateRecovery stateRecovery;
    private final SignalRepository signalRepository;
    private final PortfolioProvider portfolioProvider;
    private final int maxBarsInMemory;
    private final TraderEventPublisher publisher;

    /** Called for each newly closed bar from the stream. */
    public void onClosedBar(Bar bar) {
        for (StrategyConfig cfg : registry.activeConfigs()) {
            if (cfg.getInterval().equals(bar.getInterval())) {
                evaluate(cfg, bar.getFigi(), bar.getTs());
            }
        }
    }

    /** Recomputes signals for all active strategies over all tradable instruments. */
    public void recomputeAll() {
        List<Instrument> instruments = instrumentRepository.findAllTradable();
        for (StrategyConfig cfg : registry.activeConfigs()) {
            for (Instrument inst : instruments) {
                evaluate(cfg, inst.getFigi(), Instant.now());
            }
        }
    }

    private void evaluate(StrategyConfig cfg, String figi, Instant upTo) {
        TradingStrategy strategy = registry.factory(cfg.getId());
        if (strategy == null || !registry.isActive(cfg.getId())) {
            return;
        }
        Instrument instrument = instrumentRepository.findByFigi(figi).orElse(null);
        if (instrument == null) {
            return;
        }
        Instant from = upTo.minus(
                SeriesFactory.toDuration(cfg.getInterval()).multipliedBy(maxBarsInMemory));
        List<Bar> bars = barStore.findByFigiAndInterval(figi, cfg.getInterval(), from, upTo);
        if (bars.isEmpty()) {
            return;
        }
        BarSeries series = SeriesFactory.fromDomain(bars, DoubleNum::valueOf, maxBarsInMemory, figi);
        Optional<Signal> maybe = strategy.evaluate(series, new StrategyParams(cfg.getParams()),
                registry.signalFilter());
        if (maybe.isEmpty()) {
            return;
        }
        Signal raw = maybe.get();
        signalRepository.insert(raw);

        if (stateRecovery.isObserveOnly()) {
            log.info("observe-only: signal {} for {} not acted on", cfg.getId(), figi);
            return;
        }
        if (raw.getVerdict() != SignalVerdict.ACCEPTED) {
            log.info("signal {} for {} verdict={} reason={}", cfg.getId(), figi,
                    raw.getVerdict(), raw.getReason());
            publishSignalRejected(cfg, instrument, raw.getVerdict().name(), raw.getReason());
            return;
        }
        submitOrder(cfg, instrument, raw);
    }

    private void publishSignalRejected(StrategyConfig cfg, Instrument instrument,
                                       String rule, String reason) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("ticker", instrument.getTicker());
        payload.put("strategy", cfg.getId());
        payload.put("dirMark", "📈 LONG");
        payload.put("rule", rule);
        payload.put("reason", reason);
        payload.put("figi", instrument.getFigi());
        publisher.publish(TraderEvent.of(NotificationType.SIGNAL_REJECTED,
                NotificationLevel.INFO, payload));
    }

    private void submitOrder(StrategyConfig cfg, Instrument instrument, Signal signal) {
        Portfolio portfolio = portfolioProvider.snapshot();
        PositionSizer.SizingResult sizing = positionSizer.size(cfg.getMaxPositionPct(),
                instrument, signal.getPrice(), portfolio);
        if (sizing.getLots() <= 0) {
            log.info("position sizer returned 0 lots for {} signal {}", cfg.getId(), signal.getFigi());
            return;
        }
        OrderRequest req = OrderRequest.builder()
                .figi(signal.getFigi())
                .side(OrderSide.BUY)
                .type(cfg.getOrderType())
                .lots(sizing.getLots())
                .limitPrice(signal.getPrice())
                .strategyId(cfg.getId())
                .build();
        Order order = orderManager.submit(req);
        log.info("submitted {} lots {} for {} -> status {}", sizing.getLots(),
                signal.getFigi(), cfg.getId(), order.getStatus());
        if (order.getStatus() == com.traderbro.core.domain.enums.OrderStatus.REJECTED) {
            publishSignalRejected(cfg, instrument, "RiskGate",
                    order.getReason() == null ? "rejected" : order.getReason());
            return;
        }
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("ticker", instrument.getTicker());
        payload.put("strategy", cfg.getId());
        payload.put("dirMark", "📈 LONG");
        payload.put("typeLabel", instrument.isFuture() ? "фьючерс, " + instrument.getFutureSpec().getBasicAsset() : "акция");
        payload.put("price", signal.getPrice());
        payload.put("qty", sizing.getLots());
        payload.put("qtyUnit", instrument.isFuture() ? "контр." : "лот");
        payload.put("notional", sizing.getNotional());
        payload.put("pct", sizing.getEffectivePct().movePointRight(2).setScale(1, java.math.RoundingMode.HALF_UP));
        payload.put("stop", signal.getPrice());
        payload.put("stopPct", "0.00");
        payload.put("indicators", snapshotString(signal));
        publisher.publish(TraderEvent.of(NotificationType.SIGNAL_ENTRY,
                NotificationLevel.INFO, payload));
    }

    private String snapshotString(Signal signal) {
        StringBuilder sb = new StringBuilder();
        signal.getIndicatorSnapshot().forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(k).append("=").append(v);
        });
        return sb.length() == 0 ? "—" : sb.toString();
    }
}