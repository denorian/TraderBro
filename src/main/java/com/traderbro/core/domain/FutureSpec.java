package com.traderbro.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import lombok.Builder;
import lombok.Value;

/**
 * Futures-specific attributes (MOEX FORTS). The futures market has built-in leverage: exposure
 * is {@code price * lot * contracts}, while the margin (GO) only locks a fraction of it.
 */
@Value
@Builder
public class FutureSpec {

    String figi;
    String ticker;
    /** Underlying asset code, e.g. IMOEX / Si / BR. */
    String basicAsset;
    /** Quantity of the underlying asset per contract. */
    int lot;
    /** Price tick size. */
    BigDecimal minPriceIncrement;
    /** Cash value of one tick, rub. */
    BigDecimal minPriceIncrementAmount;
    LocalDate expirationDate;
    LocalDate firstTradeDate;
    /** Initial margin (GO) per contract, rub. */
    BigDecimal initialMargin;

    /**
     * Cash value of one price point (rub): {@code minPriceIncrementAmount / minPriceIncrement}.
     * Used by {@code FuturesPositionSizer} and futures backtesting.
     */
    public BigDecimal pointValue() {
        if (minPriceIncrement == null || minPriceIncrement.signum() == 0) {
            throw new IllegalStateException("minPriceIncrement must be positive for pointValue()");
        }
        return minPriceIncrementAmount.divide(minPriceIncrement, 8, RoundingMode.HALF_UP);
    }
}