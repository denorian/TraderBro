package com.traderbro.core.domain.spi;

import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.enums.CandleInterval;
import java.time.Instant;
import java.util.List;

/**
 * Persistence contract for bars. Implemented by the storage layer (TimescaleDB hypertable).
 * Upserts must be idempotent (upsert on unique {@code (figi, interval, ts)}), so a loader
 * can rerun safely.
 */
public interface BarStore {

    /** Idempotently writes bars (ON CONFLICT DO NOTHING / UPDATE). */
    void upsert(List<Bar> bars);

    /** Reads closed bars for a figi+interval in [from, to], ascending by ts. */
    List<Bar> findByFigiAndInterval(String figi, CandleInterval interval, Instant from, Instant to);

    /** True if a bar with this key already exists (used for incremental loads). */
    boolean exists(String figi, CandleInterval interval, Instant ts);
}