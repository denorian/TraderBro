package com.traderbro.scheduler;

import com.traderbro.api.config.StreamHealth;
import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.domain.spi.BarStore;
import com.traderbro.core.domain.spi.MarketDataProvider;
import com.traderbro.core.domain.spi.StreamSubscription;
import com.traderbro.core.event.NotificationLevel;
import com.traderbro.core.event.NotificationType;
import com.traderbro.core.event.TraderEvent;
import com.traderbro.core.event.TraderEventPublisher;
import com.traderbro.execution.killswitch.KillSwitch;
import com.traderbro.storage.InstrumentRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Owns the live candle stream: subscribes at the working interval, persists every tick, feeds
 * the {@link SignalEngine} on closed bars, and monitors staleness. Losing data for longer than
 * the threshold triggers an automatic kill-switch.
 */
@Slf4j
@RequiredArgsConstructor
public class StreamManager {

    private final MarketDataProvider marketData;
    private final BarStore barStore;
    private final InstrumentRepository instrumentRepository;
    private final SignalEngine signalEngine;
    private final KillSwitch killSwitch;
    private final CandleInterval workingInterval;
    private final Duration staleThreshold;
    private final Consumer<String> auditSink;
    private final StreamHealth health;
    private final TraderEventPublisher publisher;

    private volatile StreamSubscription subscription;
    private volatile boolean started = false;
    private volatile boolean wasDown = false;

    /** Subscribes to all tradable instruments at the working interval. Idempotent. */
    public synchronized void start() {
        if (started) {
            return;
        }
        List<Instrument> instruments = instrumentRepository.findAllTradable();
        List<String> figis = instruments.stream().map(Instrument::getFigi).toList();
        if (figis.isEmpty()) {
            log.warn("StreamManager: no tradable instruments to subscribe to");
            return;
        }
        subscription = marketData.subscribeCandles(figis, workingInterval, this::onBar);
        health.markConnected();
        started = true;
        log.info("StreamManager started for {} instruments at {}", figis.size(), workingInterval);
    }

    private void onBar(Bar bar) {
        health.markData();
        if (wasDown) {
            wasDown = false;
            publisher.publish(TraderEvent.of(NotificationType.DATA_STREAM_RESTORED,
                    NotificationLevel.INFO, Map.of("lag", health.lag().getSeconds())));
        }
        try {
            barStore.upsert(List.of(bar));
            if (bar.isFinal_()) {
                signalEngine.onClosedBar(bar);
            }
        } catch (RuntimeException e) {
            // Never let a storage error kill the data path silently.
            log.error("StreamManager: error processing bar {}: {}", bar.getFigi(), e.getMessage());
        }
    }

    /** Monitors staleness and triggers the kill-switch when data stops flowing. */
    @Scheduled(fixedDelayString = "${scheduler.staleness-monitor-ms:10000}")
    public void monitorStaleness() {
        if (!started) {
            return;
        }
        Duration lag = health.lag();
        if (lag.compareTo(staleThreshold) > 0) {
            if (!wasDown) {
                wasDown = true;
                publisher.publish(TraderEvent.of(NotificationType.DATA_STREAM_DOWN,
                        NotificationLevel.CRITICAL, Map.of("lag", lag.getSeconds())));
            }
            if (!killSwitch.isActive()) {
                String msg = "stream data stale for " + lag + " (>" + staleThreshold + "); killing switch";
                auditSink.accept(msg);
                log.error(msg);
                killSwitch.activate("stream data loss: " + lag);
            }
        }
    }
}