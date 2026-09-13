package com.traderbro.core.domain;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.Builder;
import lombok.Value;

/**
 * An OHLCV bar. Immutable value object. Times are stored in UTC.
 * <p>{@code final_} is {@code false} while the candle is still forming in the stream and
 * {@code true} once closed. Trading decisions must only rely on closed bars.
 */
@Value
@Builder
public class Bar {

    String figi;
    Instant ts;
    com.traderbro.core.domain.enums.CandleInterval interval;
    BigDecimal open;
    BigDecimal high;
    BigDecimal low;
    BigDecimal close;
    long volume;
    boolean final_;
}