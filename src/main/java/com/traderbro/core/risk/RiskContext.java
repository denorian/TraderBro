package com.traderbro.core.risk;

import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.Portfolio;
import com.traderbro.core.domain.enums.InstrumentType;
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
        BigDecimal instrumentPositionValue,
        BigDecimal totalExposure,
        int ordersLastMinute,
        boolean killSwitchActive,
        Instant now,
        Duration streamLag,
        InstrumentType instrumentType,
        /** Total GO of open futures positions, rub (0 for shares). */
        BigDecimal currentFuturesMargin) {

    /**
     * Backward-compatible constructor defaulting to a SHARE instrument with no futures margin.
     * Kept so existing callers/tests using the 12-argument form still compile.
     */
    public RiskContext(String figi, Instrument instrument, OrderSide side, long lots,
                       BigDecimal price, Portfolio portfolio, BigDecimal instrumentPositionValue,
                       BigDecimal totalExposure, int ordersLastMinute, boolean killSwitchActive,
                       Instant now, Duration streamLag) {
        this(figi, instrument, side, lots, price, portfolio, instrumentPositionValue, totalExposure,
                ordersLastMinute, killSwitchActive, now, streamLag, InstrumentType.SHARE, BigDecimal.ZERO);
    }

    public boolean isFuture() {
        return instrumentType == InstrumentType.FUTURE;
    }
}