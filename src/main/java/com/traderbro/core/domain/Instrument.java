package com.traderbro.core.domain;

import com.traderbro.core.domain.enums.InstrumentType;
import java.math.BigDecimal;
import lombok.Builder;
import lombok.Value;

/**
 * Traded instrument (equity on MOEX TQBR or futures on FORTS) as referenced by the domain.
 * Immutable value object; persisted in the {@code instruments} table.
 * <p>Strategies and risk rules are instrument-type-agnostic where possible; differences
 * (lot size, margin, tick, expiry) are encapsulated here and in {@link FutureSpec}.
 */
@Value
@Builder
public class Instrument {

    String figi;
    String ticker;
    String name;
    String isin;
    String currency;
    /** Number of base units in one lot/contract, used by {@code PositionSizer}. */
    int lot;
    /** Minimum price step (QUOTE), used to round limit prices. */
    BigDecimal minPriceIncrement;
    /** Board identifier, e.g. TQBR (shares) or FORTS board (futures). */
    String board;
    boolean tradable;
    @Builder.Default
    InstrumentType instrumentType = InstrumentType.SHARE;
    /** Futures-only attributes; null for shares. */
    FutureSpec futureSpec;

    public boolean isFuture() {
        return instrumentType == InstrumentType.FUTURE;
    }
}