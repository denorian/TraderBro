package com.traderbro.core.domain;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Value;

/** An open position reported by the broker or tracked internally. */
@Value
@Builder
public class Position {

    String figi;
    /** Signed quantity in lots: negative = short, zero = flat. */
    long lots;
    BigDecimal averagePrice;
    /** Mark price used for exposure/P&L valuation. */
    BigDecimal currentPrice;
    /** Quantity in shares (lots * lotSize), useful for reconciliation. */
    long shares;
}