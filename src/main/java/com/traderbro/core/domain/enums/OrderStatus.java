package com.traderbro.core.domain.enums;

/**
 * Lifecycle states of an order as managed by the {@code OrderManager} state machine:
 * {@code NEW -> SENT -> PARTIALLY_FILLED -> FILLED / CANCELLED / REJECTED / EXPIRED}.
 * <p>{@code NEW} is the pre-persistence state; {@code SENT} is assigned once the order is
 * handed to the broker. Terminal states are {@code FILLED}, {@code CANCELLED},
 * {@code REJECTED} and {@code EXPIRED}.
 */
public enum OrderStatus {
    NEW,
    SENT,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED,
    EXPIRED;

    public boolean isTerminal() {
        return this == FILLED || this == CANCELLED || this == REJECTED || this == EXPIRED;
    }
}