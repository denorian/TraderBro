package com.traderbro.core.domain;

import com.traderbro.core.domain.enums.Direction;
import com.traderbro.core.domain.enums.SignalVerdict;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/**
 * A computed trading signal for a strategy+instrument at a bar timestamp.
 * Persisted in the {@code signals} table with full context for reproducibility.
 */
@Value
@Builder
public class Signal {

    UUID id;
    Instant createdAt;
    String strategyId;
    String figi;
    Instant ts;
    Direction direction;
    BigDecimal price;
    /** Snapshot of indicator values at signal time (JSONB in storage). */
    Map<String, Object> indicatorSnapshot;
    SignalVerdict verdict;
    /** Human-readable reason when verdict != ACCEPTED (rejecting risk rule / filter). */
    String reason;
}