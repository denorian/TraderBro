package com.traderbro.core.domain;

import com.traderbro.core.domain.enums.CandleInterval;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/**
 * A Bill Williams AO "Twin Peaks" divergence event: two consecutive AO extrema on the same side
 * of zero where the second AO extremum is weaker in absolute terms while price makes a new
 * extremum — a momentum-exhaustion warning, not a standalone trigger.
 */
@Value
@Builder
public class DivergenceEvent {

    /** Deterministic id from (figi, interval, aoPeak2Ts, direction) — stable across rescans. */
    UUID id;
    String figi;
    CandleInterval interval;
    /** Bar of the second (extreme) AO peak — the bar the chart pin sits on. */
    Instant ts;
    Direction direction;
    /** 1 = single, 2 = double (three consecutive declining peaks with price updating). */
    int degree;
    Instant aoPeak1Ts;
    double aoPeak1Value;
    Instant aoPeak2Ts;
    double aoPeak2Value;
    double priceAtPeak1;
    double priceAtPeak2;
    /** Normalised momentum-exhaustion strength in {@code [0, 1]}. */
    double strength;
    Status status;
    Instant confirmedAt;
    Instant createdAt;

    public enum Direction {
        BULLISH,
        BEARISH
    }

    public enum Status {
        /** Second peak just formed; confirmation window still open. */
        UNCONFIRMED,
        /** AO turned toward the divergence (or price closed past Teeth) within the window. */
        CONFIRMED,
        /** Confirmation window elapsed without a turn. */
        EXPIRED
    }
}