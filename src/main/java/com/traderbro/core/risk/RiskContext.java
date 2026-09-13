package com.traderbro.core.risk;

import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.Portfolio;
import com.traderbro.core.domain.enums.OrderSide;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * Snapshot of market/portfolio state evaluated by the {@link RiskGate} before an order.
 * Immutable; produced by the execution layer, consumed by pure risk rules.
 */
public record RiskContext(
        String figi,
        Instrument instrument,
        OrderSide side,
        long lots,
        BigDecimal price,
        Portfolio portfolio,
        /** Value of the current position in {@code figi}. */
        BigDecimal instrumentPositionValue,
        /** Value of all open positions combined. */
        BigDecimal totalExposure,
        /** Orders submitted within the last 60 seconds. */
        int ordersLastMinute,
        boolean killSwitchActive,
        Instant now,
        Duration streamLag) {
}