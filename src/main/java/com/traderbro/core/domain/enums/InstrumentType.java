package com.traderbro.core.domain.enums;

/** Kind of a tradable instrument. Strategies and risk rules branch on this. */
public enum InstrumentType {
    SHARE,
    FUTURE,
    /** Spot cryptocurrency quoted against a fiat/stablecoin pair (e.g. BTCUSDT). */
    CRYPTO
}