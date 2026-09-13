package com.traderbro.core.domain.enums;

/**
 * Direction of a trading decision or position.
 * <p>Stage 1 strategies are long-only ({@link #LONG}); {@link #SHORT} is reserved for
 * the SignalFilter contract and future stages, and is never produced by stage-1 logic.
 */
public enum Direction {
    LONG,
    SHORT;

    /** Inverts the direction, used when a strategy signals a position exit. */
    public Direction opposite() {
        return this == LONG ? SHORT : LONG;
    }
}