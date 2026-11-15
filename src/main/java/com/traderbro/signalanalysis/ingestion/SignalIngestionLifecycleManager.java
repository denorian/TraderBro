package com.traderbro.signalanalysis.ingestion;

import com.traderbro.signalanalysis.SignalAnalysisProperties;
import com.traderbro.signalanalysis.SignalIngestionLifecycle;
import com.traderbro.signalanalysis.domain.TelegramChannel;
import com.traderbro.signalanalysis.repository.TelegramChannelRepository;
import com.traderbro.signalanalysis.repository.TelegramChannelSyncStateRepository;
import com.traderbro.signalanalysis.repository.TelegramIngestEventRepository;
import com.traderbro.signalanalysis.service.TelegramChannelDiscoveryService;
import com.traderbro.signalanalysis.service.TelegramHistorySyncService;
import com.traderbro.signalanalysis.telegram.TelegramSessionManager;
import com.traderbro.signalanalysis.telegram.TelegramUserClient;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Owns the single TDLib client inside TraderBro: registers the update router, runs the inbox
 * processors on a dedicated pool, schedules discovery / backfill / tail reconcile / heartbeat /
 * inbox cleanup, and shuts everything down gracefully before the context stops. Only created when
 * {@code signal-analysis.ingestion.enabled=true}. Repeated start never creates a second client;
 * recovery uses the inbox + history checkpoints in the same PostgreSQL.
 */
@Slf4j
public class SignalIngestionLifecycleManager implements SignalIngestionLifecycle, SmartLifecycle {

    private final SignalAnalysisProperties props;
    private final TelegramUserClient client;
    private final TelegramUpdateRouter router;
    private final TelegramIngestEventProcessor processor;
    private final TelegramChannelDiscoveryService discovery;
    private final TelegramHistorySyncService historySync;
    private final TelegramChannelRepository channels;
    private final TelegramChannelSyncStateRepository states;
    private final TelegramIngestEventRepository events;
    private final TelegramSessionManager session;
    private final MeterRegistry meterRegistry;

    private final ScheduledExecutorService scheduler;
    private final ExecutorService inboxExecutor;
    private final ExecutorService historyExecutor;
    private final Semaphore backfillSlots;
    private final AtomicReference<Instant> lastHeartbeat = new AtomicReference<>();
    private final AtomicReference<Instant> lastDiscoveryAt = new AtomicReference<>();

    private volatile boolean running;
    private volatile boolean started;

    public SignalIngestionLifecycleManager(SignalAnalysisProperties props,
                                           TelegramUserClient client,
                                           TelegramUpdateRouter router,
                                           TelegramIngestEventProcessor processor,
                                           TelegramChannelDiscoveryService discovery,
                                           TelegramHistorySyncService historySync,
                                           TelegramChannelRepository channels,
                                           TelegramChannelSyncStateRepository states,
                                           TelegramIngestEventRepository events,
                                           TelegramSessionManager session,
                                           MeterRegistry meterRegistry) {
        this.props = props;
        this.client = client;
        this.router = router;
        this.processor = processor;
        this.discovery = discovery;
        this.historySync = historySync;
        this.channels = channels;
        this.states = states;
        this.events = events;
        this.session = session;
        this.meterRegistry = meterRegistry;
        SignalAnalysisProperties.Ingestion cfg = props.getIngestion();
        this.scheduler = Executors.newScheduledThreadPool(3);
        this.inboxExecutor = Executors.newFixedThreadPool(cfg.getLifecycle().getInboxThreads());
        this.historyExecutor = Executors.newFixedThreadPool(cfg.getLifecycle().getHistoryThreads());
        this.backfillSlots = new Semaphore(cfg.getHistory().getMaxConcurrentChannelBackfills());
        registerGauges();
    }

    @Override
    public void start() {
        if (started) {
            return;
        }
        started = true;
        running = true;
        lastHeartbeat.set(Instant.now());
        client.onUpdate(router::accept);
        router.start();
        for (int i = 0; i < props.getIngestion().getLifecycle().getInboxThreads(); i++) {
            int worker = i;
            inboxExecutor.submit(() -> inboxLoop("inbox-" + worker));
        }
        scheduleDiscovery();
        scheduleBackfill();
        scheduleTailReconcile();
        scheduleHeartbeat();
        scheduleInboxCleanup();
        log.info("Signal-analysis ingestion started (enabled=true)");
    }

    private void scheduleDiscovery() {
        scheduler.scheduleWithFixedDelay(this::discoveryCycle, 0,
                props.getIngestion().getDiscovery().getSyncInterval().toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void discoveryCycle() {
        try {
            if (session.isReady()) {
                discovery.discoverAll();
                lastDiscoveryAt.set(Instant.now());
            }
        } catch (Exception e) {
            log.warn("Channel discovery cycle failed: {}", sanitize(e));
        }
    }

    private void scheduleBackfill() {
        scheduler.scheduleWithFixedDelay(this::backfillCycle, 5,
                props.getIngestion().getMonitoringPollInterval().toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void backfillCycle() {
        try {
            List<TelegramChannel> monitored = channels.findMonitoredSubscribed();
            for (TelegramChannel c : monitored) {
                boolean completed = states.findByChannelId(c.getId())
                        .map(s -> s.isFullBackfillCompleted()).orElse(false);
                if (!completed && backfillSlots.tryAcquire()) {
                    historyExecutor.submit(() -> {
                        try {
                            historySync.fullBackfill(c);
                        } catch (Exception e) {
                            log.warn("Full backfill failed for channel {}: {}", c.getTelegramChatId(),
                                    sanitize(e));
                        } finally {
                            backfillSlots.release();
                        }
                    });
                }
            }
        } catch (Exception e) {
            log.warn("Backfill cycle failed: {}", sanitize(e));
        }
    }

    private void scheduleTailReconcile() {
        scheduler.scheduleWithFixedDelay(this::tailCycle, 60,
                props.getIngestion().getHistory().getTailSyncInterval().toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void tailCycle() {
        try {
            for (TelegramChannel c : channels.findMonitoredSubscribed()) {
                historyExecutor.submit(() -> {
                    try {
                        historySync.tailReconcile(c);
                    } catch (Exception e) {
                        log.warn("Tail reconcile failed for channel {}: {}", c.getTelegramChatId(),
                                sanitize(e));
                    }
                });
            }
        } catch (Exception e) {
            log.warn("Tail reconcile cycle failed: {}", sanitize(e));
        }
    }

    private void scheduleHeartbeat() {
        scheduler.scheduleWithFixedDelay(this::heartbeat, 0,
                props.getIngestion().getLifecycle().getHeartbeatInterval().toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void heartbeat() {
        lastHeartbeat.set(Instant.now());
        meterRegistry.gauge("traderbro_signal_ingest_inbox_pending", events.pendingCount());
        meterRegistry.gauge("traderbro_signal_ingest_inbox_failed", events.failedCount());
        meterRegistry.gauge("traderbro_signal_ingest_active_channels",
                channels.findMonitoredSubscribed().size());
    }

    private void scheduleInboxCleanup() {
        SignalAnalysisProperties.Ingestion.Inbox inbox = props.getIngestion().getInbox();
        scheduler.scheduleWithFixedDelay(() -> {
            Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(
                    inbox.getRawEventRetentionDays()));
            int n = events.cleanupBefore(cutoff);
            if (n > 0) {
                log.debug("Cleaned {} processed/ignored inbox events", n);
            }
        }, inbox.getCleanupInterval().toMillis(), inbox.getCleanupInterval().toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void inboxLoop(String workerId) {
        while (running) {
            try {
                processor.runOnce(workerId);
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("Inbox worker {} error: {}", workerId, sanitize(e));
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void registerGauges() {
        Gauge.builder("traderbro_signal_ingest_lifecycle_heartbeat",
                () -> lastHeartbeat.get() == null ? 0 : lastHeartbeat.get().toEpochMilli())
                .register(meterRegistry);
    }

    @Override
    public boolean isActive() {
        return running && session.isReady();
    }

    @Override
    public Instant lastHeartbeat() {
        return lastHeartbeat.get();
    }

    @Override
    public Instant lastDiscoveryAt() {
        return lastDiscoveryAt.get();
    }

    @Override
    public String triggerDiscovery() {
        if (!running) {
            return null;
        }
        UUID runId = UUID.randomUUID();
        scheduler.execute(() -> {
            try {
                discovery.discoverAll();
                lastDiscoveryAt.set(Instant.now());
            } catch (Exception e) {
                log.warn("Manual discovery failed: {}", sanitize(e));
            }
        });
        return runId.toString();
    }

    @Override
    public void stop() {
        if (!started) {
            return;
        }
        running = false;
        started = false;
        router.stop();
        scheduler.shutdownNow();
        inboxExecutor.shutdownNow();
        historyExecutor.shutdownNow();
        try {
            client.closeAndWait();
        } catch (Exception e) {
            log.warn("TDLib client close during shutdown: {}", sanitize(e));
        }
        log.info("Signal-analysis ingestion stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void close() {
        stop();
    }

    private static String sanitize(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }
}