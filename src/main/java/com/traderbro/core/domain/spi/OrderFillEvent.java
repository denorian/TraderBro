package com.traderbro.core.domain.spi;

import com.traderbro.core.domain.enums.OrderStatus;
import java.math.BigDecimal;

/**
 * A fill/status event for an order, emitted by the broker's order stream. Consumed by
 * {@code OrderManager} to advance its state machine on partial/final fills.
 */
public record OrderFillEvent(
        String brokerOrderId,
        long filledLots,
        BigDecimal avgFillPrice,
        OrderStatus status,
        long lotsRequested) {
}