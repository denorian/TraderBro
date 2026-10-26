package com.traderbro.scheduler;

import com.traderbro.api.config.CryptoProperties;
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

/**
 * Subscribes the Binance candle stream for the tracked crypto pairs at each configured timeframe
 * and persists every tick. Closed bars are fed into {@link IndicatorSnapshotService} so crypto
 * participates in Alligator/AO/divergence/angulation analytics. Live crypto signals/orders are
 * intentionally not produced here (crypto is analysis + backtest only in this stage).
 */
@Slf4j
public class CryptoStreamRunner implements ApplicationRunner {

    private final MarketDataProvider marketData;
    private final BarStore barStore;
    private final IndicatorSnapshotService snapshotService;
    private final CryptoProperties props;

    private final List<StreamSubscription> subscriptions = new ArrayList<>();

    public CryptoStreamRunner(MarketDataProvider marketData, BarStore barStore,
                              IndicatorSnapshotService snapshotService, CryptoProperties props) {
        this.marketData = marketData;
        this.barStore = barStore;
        this.snapshotService = snapshotService;
        this.props = props;
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
            barStore.upsert(List.of(bar));
            if (bar.isFinal_()) {
                snapshotService.onClosedBar(bar);
            }
        } catch (RuntimeException e) {
            log.error("crypto stream: error processing bar {}: {}", bar.getFigi(), e.getMessage());
        }
    }
}