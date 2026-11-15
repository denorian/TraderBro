package com.traderbro.signalanalysis.ingestion;

import com.traderbro.signalanalysis.SignalAnalysisProperties;
import com.traderbro.signalanalysis.domain.ChangeType;
import com.traderbro.signalanalysis.domain.TelegramChannel;
import com.traderbro.signalanalysis.domain.TelegramIngestEvent;
import com.traderbro.signalanalysis.repository.TelegramChannelRepository;
import com.traderbro.signalanalysis.repository.TelegramIngestEventRepository;
import com.traderbro.signalanalysis.service.TelegramChannelDiscoveryService;
import com.traderbro.signalanalysis.service.TelegramMessageService;
import com.traderbro.signalanalysis.telegram.TelegramMessage;
import com.traderbro.signalanalysis.telegram.TelegramUserClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Claims inbox events in batches and applies them idempotently. Per-event result counters feed the
 * {@code traderbro_signal_ingest_events_total{type,result}} metric. Transient errors retry with
 * exponential backoff + jitter; a poison event is failed after {@code max-attempts} without
 * stopping ingestion.
 */
@Slf4j
public class TelegramIngestEventProcessor {

    private final TelegramIngestEventRepository events;
    private final TelegramChannelRepository channels;
    private final TelegramMessageService messageService;
    private final TelegramChannelDiscoveryService discovery;
    private final TelegramUserClient client;
    private final ObjectMapper json;
    private final SignalAnalysisProperties.Ingestion.Inbox inboxCfg;
    private final Counter successCounter;
    private final Counter retryCounter;
    private final Counter failedCounter;

    public TelegramIngestEventProcessor(TelegramIngestEventRepository events,
                                        TelegramChannelRepository channels,
                                        TelegramMessageService messageService,
                                        TelegramChannelDiscoveryService discovery,
                                        TelegramUserClient client,
                                        ObjectMapper json,
                                        SignalAnalysisProperties props,
                                        MeterRegistry meterRegistry) {
        this.events = events;
        this.channels = channels;
        this.messageService = messageService;
        this.discovery = discovery;
        this.client = client;
        this.json = json;
        this.inboxCfg = props.getIngestion().getInbox();
        this.successCounter = meterRegistry.counter("traderbro_signal_ingest_events_total",
                "result", "processed");
        this.retryCounter = meterRegistry.counter("traderbro_signal_ingest_events_total",
                "result", "retry");
        this.failedCounter = meterRegistry.counter("traderbro_signal_ingest_events_total",
                "result", "failed");
    }

    /** Process one claim batch. Returns the number of events handled. */
    public int runOnce(String workerId) {
        Instant now = Instant.now();
        List<TelegramIngestEvent> batch = events.claimBatch(now, workerId,
                inboxCfg.getClaimBatchSize());
        for (TelegramIngestEvent e : batch) {
            try {
                handle(e);
                events.markProcessed(e.getReceivedSeq(), Instant.now());
                successCounter.increment();
            } catch (Exception ex) {
                if (e.getAttempts() >= inboxCfg.getMaxAttempts()) {
                    events.markFailed(e.getReceivedSeq(), sanitize(ex));
                    failedCounter.increment();
                } else {
                    events.markRetry(e.getReceivedSeq(), nextRetryAt(e), sanitize(ex));
                    retryCounter.increment();
                }
            }
        }
        return batch.size();
    }

    private void handle(TelegramIngestEvent e) {
        switch (e.getEventType()) {
            case "NEW_MESSAGE" -> handleNewMessage(e);
            case "EDIT" -> handleEdit(e);
            case "DELETE" -> handleDelete(e);
            case "CHAT_METADATA" -> discovery.applyMetadata(parse(e.getPayload(),
                    TelegramIngestEventWriter.MetadataEnvelope.class), Instant.now());
            default -> log.debug("Ignoring unknown ingest event type {}", e.getEventType());
        }
    }

    private void handleNewMessage(TelegramIngestEvent e) {
        TelegramMessage m = parse(e.getPayload(), TelegramMessage.class);
        Optional<TelegramChannel> ch = channels.findByChatId(e.getTelegramChatId());
        if (ch.isEmpty() || !ch.get().isMonitoringEnabled()) {
            return;
        }
        messageService.apply(ch.get(), m, ChangeType.LIVE_NEW, Instant.now(), e.getId());
    }

    private void handleEdit(TelegramIngestEvent e) {
        Optional<TelegramChannel> ch = channels.findByChatId(e.getTelegramChatId());
        if (ch.isEmpty() || !ch.get().isMonitoringEnabled()) {
            return;
        }
        if (e.getTelegramMessageId() == null) {
            return;
        }
        // Refetch the full, consistent snapshot so updateMessageEdited and updateMessageContent
        // (two updates for one physical edit) collapse into a single version.
        TelegramMessage m = client.getMessage(e.getTelegramChatId(), e.getTelegramMessageId()).join();
        messageService.apply(ch.get(), m, ChangeType.EDIT, Instant.now(), e.getId());
    }

    private void handleDelete(TelegramIngestEvent e) {
        TelegramIngestEventWriter.DeletePayload p = parse(e.getPayload(),
                TelegramIngestEventWriter.DeletePayload.class);
        Optional<TelegramChannel> ch = channels.findByChatId(p.chatId());
        if (ch.isEmpty() || !ch.get().isMonitoringEnabled()) {
            return;
        }
        messageService.applyDelete(ch.get(), p.messageId(), p.permanent(), p.fromCache(),
                Instant.now());
    }

    private Instant nextRetryAt(TelegramIngestEvent e) {
        Duration initial = inboxCfg.getInitialRetryDelay();
        Duration max = inboxCfg.getMaxRetryDelay();
        long delayMillis = Math.min(max.toMillis(),
                initial.toMillis() * (1L << Math.min(e.getAttempts() - 1, 10)));
        delayMillis = (long) (delayMillis * (0.8 + 0.4 * ThreadLocalRandom.current().nextDouble()));
        return Instant.now().plusMillis(delayMillis);
    }

    private <T> T parse(String payload, Class<T> type) {
        try {
            return json.readValue(payload, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse inbox payload for " + type, e);
        }
    }

    private static String sanitize(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }
}