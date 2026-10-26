package com.traderbro.core.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Builder;
import lombok.Value;

/**
 * Crypto-specific attributes for a spot pair on Binance (e.g. BTCUSDT). The {@code figi} of a
 * crypto instrument is its Binance symbol, so the pair is self-describing; {@code baseAsset}
 * and {@code quoteAsset} carry the leg breakdown (BTC + USDT).
 */
@Value
@Builder
public class CryptoSpec {

    String figi;
    String ticker;
    /** Base asset code, e.g. BTC. */
    String baseAsset;
    /** Quote asset code, e.g. USDT. */
    String quoteAsset;
    /** Minimum price step (tick size) from Binance {@code exchangeInfo}. */
    BigDecimal tickSize;
    /** Minimum quantity step (step size) from Binance {@code exchangeInfo}. */
    BigDecimal stepSize;
    /** Minimum notional order value in quote currency, from {@code exchangeInfo}. */
    BigDecimal minNotional;
    /** Listing date from {@code exchangeInfo}. */
    LocalDate onboardDate;

    /**
     * Validates that the spec is usable (positive tick/step sizes). Throws
     * {@link IllegalStateException} otherwise so a malformed {@code exchangeInfo} entry fails fast.
     */
    public void assertValid() {
        if (tickSize == null || tickSize.signum() <= 0) {
            throw new IllegalStateException("crypto tickSize must be positive for " + figi);
        }
        if (stepSize == null || stepSize.signum() <= 0) {
            throw new IllegalStateException("crypto stepSize must be positive for " + figi);
        }
    }
}