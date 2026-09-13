package com.traderbro.core.domain;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.Builder;
import lombok.Value;

/**
 * Aggregate portfolio valuation snapshot. Money uses {@link BigDecimal}.
 * {@code dayPnL} is realized+unrealized P&L of the current trading day.
 */
@Value
@Builder
public class Portfolio {

    BigDecimal totalValue;
    BigDecimal cash;
    BigDecimal securitiesValue;
    BigDecimal dayPnL;
    BigDecimal dayPnLPercent;
    Instant at;
}