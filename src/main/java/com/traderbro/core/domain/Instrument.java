package com.traderbro.core.domain;

import lombok.Builder;
import lombok.Value;

/**
 * Traded instrument (equity on MOEX TQBR board) as referenced by the domain.
 * Immutable value object; persisted in the {@code instruments} table.
 */
@Value
@Builder
public class Instrument {

    String figi;
    String ticker;
    String name;
    String isin;
    String currency;
    /** Number of shares in one lot, used by {@code PositionSizer} rounding. */
    int lot;
    /** Minimum price step (QUOTE), used to round limit prices. */
    java.math.BigDecimal minPriceIncrement;
    /** Board identifier, expected to be TQBR. */
    String board;
    boolean tradable;
}