package com.traderbro.core.event;

/**
 * Port through which the engine emits trading events. Implemented in the {@code notify}
 * package (dedup, rate-limit, persistence, Telegram). Keeping this interface in {@code core}
 * lets the engine emit events without depending on {@code notify}.
 */
public interface TraderEventPublisher {

    void publish(TraderEvent event);
}