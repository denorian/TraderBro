package com.traderbro.signalanalysis.ingestion;

import com.traderbro.signalanalysis.telegram.TelegramUpdate;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * Routes {@link TelegramUpdate} events from the TDLib adapter into the durable inbox writer. A
 * plain, non-throwing pass-through: all heavy work (DB, fetch) happens in the processor. The
 * {@code active} flag is driven by the lifecycle manager so routing can be paused cleanly.
 */
@Slf4j
public class TelegramUpdateRouter {

    private final TelegramIngestEventWriter writer;
    private final AtomicBoolean active = new AtomicBoolean(true);

    public TelegramUpdateRouter(TelegramIngestEventWriter writer) {
        this.writer = writer;
    }

    public void accept(TelegramUpdate update) {
        if (!active.get()) {
            return;
        }
        try {
            writer.offer(update);
        } catch (Exception e) {
            // A routing failure must never break the native callback loop; the event will be
            // re-obtained by TDLib on reconnect (we do not silently drop edits/deletes).
            log.warn("Update routing failed: {}", sanitize(e));
        }
    }

    public void setActive(boolean active) {
        this.active.set(active);
    }

    public boolean isHistorySuspended() {
        return writer.isHistorySuspended();
    }

    public void start() {
        writer.start();
    }

    public void stop() {
        writer.close();
    }

    private static String sanitize(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }
}