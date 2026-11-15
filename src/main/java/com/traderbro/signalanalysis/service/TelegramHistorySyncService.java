package com.traderbro.signalanalysis.service;

import com.traderbro.signalanalysis.SignalAnalysisProperties;
import com.traderbro.signalanalysis.domain.ChangeType;
import com.traderbro.signalanalysis.domain.SyncRunStatus;
import com.traderbro.signalanalysis.domain.SyncRunType;
import com.traderbro.signalanalysis.domain.TelegramChannel;
import com.traderbro.signalanalysis.domain.TelegramChannelSyncState;
import com.traderbro.signalanalysis.domain.TelegramSyncRun;
import com.traderbro.signalanalysis.ingestion.TelegramUpdateRouter;
import com.traderbro.signalanalysis.repository.TelegramChannelSyncStateRepository;
import com.traderbro.signalanalysis.repository.TelegramMessageRepository;
import com.traderbro.signalanalysis.repository.TelegramSyncRunRepository;
import com.traderbro.signalanalysis.telegram.TelegramMessage;
import com.traderbro.signalanalysis.telegram.TelegramUserClient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * Full history backfill to the first available message of each monitored channel, plus a periodic
 * tail reconcile. Backfill resumes from the saved checkpoint ({@code oldest_loaded_message_id})
 * after a restart and never re-reads the whole archive. Only an empty page confirms the beginning
 * of history — a short non-empty page is not treated as the start. A channel with an incomplete
 * archive is marked DEGRADED, not ACTIVE.
 */
@Slf4j
public class TelegramHistorySyncService {

    private final TelegramUserClient client;
    private final TelegramMessageService messageService;
    private final TelegramMessageRepository messages;
    private final TelegramChannelSyncStateRepository states;
    private final TelegramSyncRunRepository syncRuns;
    private final TelegramUpdateRouter router;
    private final SignalAnalysisProperties.Ingestion.History cfg;

    public TelegramHistorySyncService(TelegramUserClient client,
                                      TelegramMessageService messageService,
                                      TelegramMessageRepository messages,
                                      TelegramChannelSyncStateRepository states,
                                      TelegramSyncRunRepository syncRuns,
                                      TelegramUpdateRouter router,
                                      SignalAnalysisProperties props) {
        this.client = client;
        this.messageService = messageService;
        this.messages = messages;
        this.states = states;
        this.syncRuns = syncRuns;
        this.router = router;
        this.cfg = props.getIngestion().getHistory();
    }

    /** Run (or resume) the full backfill for a channel. No-op if already completed. */
    public void fullBackfill(TelegramChannel channel) {
        if (!channel.isMonitoringEnabled() || !cfg.isFullBackfillEnabled()) {
            log.info("fullBackfill skip: monitoring={} enabled={}",
                    channel.isMonitoringEnabled(), cfg.isFullBackfillEnabled());
            return;
        }
        TelegramChannelSyncState state = states.findByChannelId(channel.getId())
                .orElse(TelegramChannelSyncState.builder()
                        .channelId(channel.getId())
                        .updatedAt(Instant.now())
                        .build());
        if (state.isFullBackfillCompleted()) {
            log.info("fullBackfill skip: channel {} already completed", channel.getTelegramChatId());
            return;
        }
        // Do not run two backfills of the same channel concurrently.
        if (syncRuns.hasActiveRun(channel.getId(), SyncRunType.FULL_BACKFILL)) {
            log.info("fullBackfill skip: active run exists for channel {}",
                    channel.getTelegramChatId());
            return;
        }
        log.info("fullBackfill start: channel {}", channel.getTelegramChatId());
        TelegramSyncRun run = syncRuns.findRunning(channel.getId(), SyncRunType.FULL_BACKFILL)
                .orElseGet(() -> {
                    TelegramSyncRun r = TelegramSyncRun.builder()
                            .id(UUID.randomUUID())
                            .channelId(channel.getId())
                            .runType(SyncRunType.FULL_BACKFILL)
                            .status(SyncRunStatus.RUNNING)
                            .requestedAt(Instant.now())
                            .build();
                    syncRuns.insert(r);
                    return r;
                });
        syncRuns.markStarted(run.getId(), Instant.now());

        long from = state.getOldestLoadedMessageId() == null
                ? 0 : state.getOldestLoadedMessageId();
        int noProgress = 0;
        int loaded = 0;
        int created = 0;
        int edited = 0;
        Instant now = Instant.now();
        try {
            while (true) {
                if (router.isHistorySuspended()) {
                    break; // backpressure: live ingestion takes priority; resume later
                }
                List<TelegramMessage> page = client.getChatHistory(
                        channel.getTelegramChatId(), from, cfg.getPageSize()).join();
                if (page.isEmpty()) {
                    state.setFullBackfillCompleted(true);
                    break;
                }
                long minId = page.stream().mapToLong(TelegramMessage::messageId).min()
                        .orElse(from);
                long maxId = page.stream().mapToLong(TelegramMessage::messageId).max().orElse(0);
                for (TelegramMessage m : page) {
                    boolean known = messages.exists(channel.getId(), m.messageId());
                    ChangeType ct = known ? ChangeType.SYNC_CORRECTION : ChangeType.BACKFILL;
                    TelegramMessageService.ApplyResult r =
                            messageService.apply(channel, m, ct, now, run.getId());
                    if (r == TelegramMessageService.ApplyResult.CREATED) {
                        created++;
                    } else if (r == TelegramMessageService.ApplyResult.UPDATED) {
                        edited++;
                    }
                    loaded++;
                }
                state.setOldestLoadedMessageId(minId);
                state.setOldestLoadedMessageSentAt(minOfSent(page));
                state.setNewestLoadedMessageId(Math.max(
                        state.getNewestLoadedMessageId() == null ? 0 : state.getNewestLoadedMessageId(),
                        maxId));
                state.setLastSuccessfulSyncAt(Instant.now());
                state.setSyncAttempts(state.getSyncAttempts() + 1);
                state.setUpdatedAt(Instant.now());
                states.upsert(state);
                syncRuns.updateCursor(run.getId(), minId, loaded, created, edited);

                if (minId == from) {
                    noProgress++;
                    if (noProgress >= cfg.getMaxNoProgressPages()) {
                        failRun(run, minId, "NO_PROGRESS", loaded, created, edited);
                        return;
                    }
                } else {
                    noProgress = 0;
                }
                from = minId;
                pace();
            }
            state.setLastTailSyncAt(Instant.now());
            states.upsert(state);
            syncRuns.markCompleted(run.getId(), Instant.now(), from, loaded, created, edited);
            log.info("Backfill {} for channel {}: {} messages, {} created, {} edited",
                    state.isFullBackfillCompleted() ? "complete" : "paused",
                    channel.getTelegramChatId(), loaded, created, edited);
        } catch (Exception e) {
            failRun(run, from, "BACKFILL_FAILED", loaded, created, edited);
            log.warn("Backfill failed for channel {}:", channel.getTelegramChatId(), e);
        }
    }

    /** Periodic reconcile of the last {@code tail-size} messages (no full rescan). */
    public void tailReconcile(TelegramChannel channel) {
        if (!channel.isMonitoringEnabled()) {
            return;
        }
        if (syncRuns.hasActiveRun(channel.getId(), SyncRunType.TAIL_RECONCILE)) {
            return;
        }
        TelegramSyncRun run = TelegramSyncRun.builder()
                .id(UUID.randomUUID())
                .channelId(channel.getId())
                .runType(SyncRunType.TAIL_RECONCILE)
                .status(SyncRunStatus.RUNNING)
                .requestedAt(Instant.now())
                .build();
        syncRuns.insert(run);
        syncRuns.markStarted(run.getId(), Instant.now());
        int loaded = 0;
        int edited = 0;
        try {
            List<TelegramMessage> tail = client.getChatHistory(
                    channel.getTelegramChatId(), 0, cfg.getTailSize()).join();
            for (TelegramMessage m : tail) {
                boolean known = messages.exists(channel.getId(), m.messageId());
                ChangeType ct = known ? ChangeType.SYNC_CORRECTION : ChangeType.LIVE_NEW;
                TelegramMessageService.ApplyResult r =
                        messageService.apply(channel, m, ct, Instant.now(), run.getId());
                if (r == TelegramMessageService.ApplyResult.UPDATED) {
                    edited++;
                }
                loaded++;
            }
            TelegramChannelSyncState state = states.findByChannelId(channel.getId())
                    .orElse(TelegramChannelSyncState.builder()
                            .channelId(channel.getId()).updatedAt(Instant.now()).build());
            state.setLastTailSyncAt(Instant.now());
            state.setLastSuccessfulSyncAt(Instant.now());
            state.setUpdatedAt(Instant.now());
            states.upsert(state);
            syncRuns.markCompleted(run.getId(), Instant.now(), null, loaded, 0, edited);
        } catch (Exception e) {
            syncRuns.markFailed(run.getId(), Instant.now(), null, "TAIL_FAILED", sanitize(e));
        }
    }

    private void failRun(TelegramSyncRun run, Long cursor, String code,
                         int loaded, int created, int edited) {
        syncRuns.updateCursor(run.getId(), cursor, loaded, created, edited);
        syncRuns.markFailed(run.getId(), Instant.now(), cursor, code, "History backfill could not complete");
    }

    private static Instant minOfSent(List<TelegramMessage> page) {
        return page.stream().map(TelegramMessage::sentAt).min(Instant::compareTo).orElse(null);
    }

    private void pace() {
        long delay = cfg.getRequestPacingDelay().toMillis();
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String sanitize(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }
}