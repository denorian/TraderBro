package com.traderbro.scheduler;

import com.traderbro.api.config.CryptoProperties;
import com.traderbro.api.config.StreamHealth;
import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.domain.spi.BarStore;
import com.traderbro.core.domain.spi.MarketDataProvider;
import com.traderbro.core.domain.spi.StreamSubscription;
import com.traderbro.core.indicators.IndicatorSnapshotService;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Subscribes the Binance candle stream for the tracked crypto pairs at each configured timeframe
 * and persists closed bars. In-progress ticks only keep the stream alive and are not persisted —
 * the {@code bars} upsert is {@code ON CONFLICT DO NOTHING}, so writing a partial candle would
 * permanently shadow the real final bar (its OHLCV would be the first tick's). Closed bars are
 * fed into {@link IndicatorSnapshotService} and {@link SignalEngine} so crypto participates in
 * Alligator/AO/divergence/angulation analytics and produces live signals on bar close (orders are
 * still blocked in {@link SignalEngine} for crypto — analysis + backtest only in this stage).
 */
@Slf4j
public class CryptoStreamRunner implements ApplicationRunner {

    private final MarketDataProvider marketData;
    private final BarStore barStore;
    private final IndicatorSnapshotService snapshotService;
    private final SignalEngine signalEngine;
    private final CryptoProperties props;
    private final StreamHealth cryptoHealth;

    private final List<StreamSubscription> subscriptions = new ArrayList<>();

    public CryptoStreamRunner(MarketDataProvider marketData, BarStore barStore,
                              IndicatorSnapshotService snapshotService, SignalEngine signalEngine,
                              CryptoProperties props, StreamHealth cryptoHealth) {
        this.marketData = marketData;
        this.barStore = barStore;
        this.snapshotService = snapshotService;
        this.signalEngine = signalEngine;
        this.props = props;
        this.cryptoHealth = cryptoHealth;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isEnabled() || props.getPairs().isEmpty()) {
            log.info("crypto stream disabled");
            return;
        }
        for (String tf : props.getTimeframes()) {
            CandleInterval interval = CandleInterval.valueOf(tf);
            try {
                StreamSubscription sub = marketData.subscribeCandles(
                        props.getPairs(), interval, this::onBar);
                subscriptions.add(sub);
                log.info("crypto stream subscribed: {} pairs @ {}", props.getPairs().size(), interval);
            } catch (RuntimeException e) {
                log.error("crypto stream subscribe failed for {}: {}", interval, e.getMessage());
            }
        }
    }

    private void onBar(Bar bar) {
        try {
            // Persist and analyze only closed candles. In-progress ticks never touch the bars
            // table (the upsert is ON CONFLICT DO NOTHING, so a partial candle would shadow the
            // real final bar) nor the indicator snapshots.
            if (bar.isFinal_()) {
                barStore.upsert(List.of(bar));
                snapshotService.onClosedBar(bar);
                signalEngine.onClosedBar(bar);
            }
        } catch (RuntimeException e) {
            log.error("crypto stream: error processing bar {}: {}", bar.getFigi(), e.getMessage());
        }
    }

    /** Mirrors a permanently-degraded crypto subscription (reconnect budget exhausted) into the
     * crypto stream health so it shows up in {@code /api/status} instead of only in logs. */
    @Scheduled(fixedDelayString = "${scheduler.staleness-monitor-ms:10000}")
    public void monitorHealth() {
        boolean degraded = subscriptions.stream().anyMatch(StreamSubscription::isDegraded);
        if (degraded) {
            cryptoHealth.markDegraded();
        } else {
            cryptoHealth.clearDegraded();
        }
    }
}