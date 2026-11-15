package com.traderbro.signalanalysis.ingestion;

import com.traderbro.signalanalysis.domain.IngestEventStatus;
import com.traderbro.signalanalysis.domain.TelegramIngestEvent;
import com.traderbro.signalanalysis.repository.TelegramIngestEventRepository;
import com.traderbro.signalanalysis.telegram.TelegramUpdate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Persists updates to the durable inbox ({@code telegram_ingest_events}) on a dedicated writer
 * thread, off the native TDLib callback. A bounded queue provides backpressure: when the queue is
 * full the callback blocks, which also pauses TDLib update delivery; the history loader is told to
 * suspend so the pipeline never drops an edit/delete event.
 */
@Slf4j
public class TelegramIngestEventWriter implements AutoCloseable {

    private final TelegramIngestEventRepository events;
    private final ObjectMapper json;
    private final BlockingQueue<TelegramUpdate> queue;
    private final AtomicBoolean historySuspended = new AtomicBoolean(false);
    private final Thread thread;
    private volatile boolean running = true;

    public TelegramIngestEventWriter(TelegramIngestEventRepository events, ObjectMapper json,
                                     int queueCapacity) {
        this.events = events;
        this.json = json;
        this.queue = new LinkedBlockingQueue<>(queueCapacity);
        this.thread = new Thread(this::drain, "signal-ingest-writer");
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    /** Enqueue an update. Blocks when the queue is full (backpressure on the callback). */
    public void offer(TelegramUpdate update) {
        try {
            queue.put(update);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (queue.remainingCapacity() == 0) {
            historySuspended.set(true);
        }
    }

    public boolean isHistorySuspended() {
        return historySuspended.get();
    }

    private void drain() {
        while (running) {
            try {
                TelegramUpdate update = queue.take();
                List<TelegramIngestEvent> eventsToInsert = toEvents(update);
                for (TelegramIngestEvent e : eventsToInsert) {
                    try {
                        events.insert(e);
                    } catch (Exception ex) {
                        // Never lose an update silently: keep retrying the durable insert.
                        log.warn("Inbox insert failed for {}; retrying: {}",
                                e.getEventType(), sanitize(ex));
                        events.insert(e);
                    }
                }
                if (queue.remainingCapacity() > 0) {
                    historySuspended.set(false);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("Inbox writer error: {}", sanitize(e));
            }
        }
    }

    List<TelegramIngestEvent> toEvents(TelegramUpdate update) {
        Instant now = Instant.now();
        List<TelegramIngestEvent> out = new ArrayList<>();
        if (update instanceof TelegramUpdate.NewMessage n) {
            out.add(event("NEW_MESSAGE", n.message().chatId(), n.message().messageId(),
                    "new:" + n.message().chatId() + ":" + n.message().messageId(),
                    write(n.message()), now));
        } else if (update instanceof TelegramUpdate.MessageEdited e) {
            // No dedup key: both updateMessageEdited and updateMessageContent must be processed;
            // the idempotent snapshot apply collapses them into a single version.
            out.add(event("EDIT", e.chatId(), e.messageId(), null,
                    write(new EditPayload(e.chatId(), e.messageId(), e.editedAt())), now));
        } else if (update instanceof TelegramUpdate.MessageContent c) {
            out.add(event("EDIT", c.chatId(), c.messageId(), null,
                    write(new EditPayload(c.chatId(), c.messageId(), null)), now));
        } else if (update instanceof TelegramUpdate.DeleteMessages d) {
            for (long id : d.messageIds()) {
                out.add(event("DELETE", d.chatId(), id,
                        "del:" + d.chatId() + ":" + id,
                        write(new DeletePayload(d.chatId(), id, d.permanent(), d.fromCache())), now));
            }
        } else if (update instanceof TelegramUpdate.NewChat c) {
            out.add(event("CHAT_METADATA", c.chat().chatId(), null,
                    "chat:" + c.chat().chatId(),
                    write(new MetadataEnvelope("chat", write(c.chat()))), now));
        } else if (update instanceof TelegramUpdate.Supergroup s) {
            out.add(event("CHAT_METADATA", s.supergroup().id(), null,
                    "chat:" + s.supergroup().id(),
                    write(new MetadataEnvelope("supergroup", write(s.supergroup()))), now));
        }
        return out;
    }

    private TelegramIngestEvent event(String type, long chatId, Long messageId, String dedupKey,
                                      String payload, Instant now) {
        return TelegramIngestEvent.builder()
                .id(UUID.randomUUID())
                .eventType(type)
                .telegramChatId(chatId)
                .telegramMessageId(messageId)
                .dedupKey(dedupKey)
                .payload(payload)
                .receivedAt(now)
                .status(IngestEventStatus.NEW)
                .attempts(0)
                .availableAt(now)
                .build();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Payload for an edit event; the processor refetches the full message. */
    public record EditPayload(long chatId, long messageId, Instant editedAt) {
    }

    /** Payload for a delete event. */
    public record DeletePayload(long chatId, long messageId, boolean permanent, boolean fromCache) {
    }

    /** Wrapper that discriminates {@code TelegramChat} vs {@code TelegramSupergroup} payloads. */
    public record MetadataEnvelope(String kind, String json) {
    }

    private static String sanitize(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
    }
}