package com.traderbro.core.domain;

import com.traderbro.core.domain.enums.CandleInterval;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/**
 * Price angulation away from the blue Jaw (Alligator) line, measured on the window from the last
 * Jaw touch ({@code anchorTs}) to the extreme bar of an AO divergence. Angulation is an amplifier
 * / validity filter for reversal signals — a reversal without angulation is invalid per Williams.
 */
@Value
@Builder
public class AngulationEvent {

    String figi;
    CandleInterval interval;
    Instant ts;
    /** The divergence event whose extreme peak this angulation is anchored on. */
    UUID divergenceEventId;
    Direction direction;
    /** Linear-regression slope of the price line, in ATR per bar. */
    double priceSlopeAtr;
    /** Linear-regression slope of the Jaw line, in ATR per bar. */
    double jawSlopeAtr;
    /** {@code |priceSlope - jawSlope|} in ATR per bar. */
    double angleAtr;
    Instant anchorTs;
    int barsInWindow;
    Instant createdAt;

    public enum Direction {
        /** Price walking away above the Jaw (bullish departure). */
        AWAY_UP,
        /** Price walking away below the Jaw (bearish departure). */
        AWAY_DOWN
    }
}