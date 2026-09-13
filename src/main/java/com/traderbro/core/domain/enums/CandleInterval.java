package com.traderbro.core.domain.enums;

/**
 * Domain candle intervals supported by TraderBro. Converted to the broker SDK's own
 * interval type in the data/mapper layer; the domain never depends on the SDK.
 */
public enum CandleInterval {
    ONE_MIN,
    FIVE_MIN,
    FIFTEEN_MIN,
    ONE_HOUR,
    ONE_DAY
}