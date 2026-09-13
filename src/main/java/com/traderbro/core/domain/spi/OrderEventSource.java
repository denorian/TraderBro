package com.traderbro.core.domain.spi;

import java.util.function.Consumer;

/**
 * Source of order fill/status events (broker order stream). Implemented by the data layer;
 * keeps {@code execution} free of broker SDK types.
 */
public interface OrderEventSource {

    /** Registers a consumer for fill events. Idempotent; only one subscription is retained. */
    void subscribe(Consumer<OrderFillEvent> onFill);
}