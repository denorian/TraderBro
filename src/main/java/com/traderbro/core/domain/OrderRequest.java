package com.traderbro.core.domain;

import com.traderbro.core.domain.enums.OrderSide;
import com.traderbro.core.domain.enums.OrderType;
import java.math.BigDecimal;
import lombok.Builder;
import lombok.Value;

/**
 * Domain request to place an order. Carries the client-generated {@code clientOrderId}
 * used for deduplication and idempotency across restarts.
 */
@Value
@Builder
public class OrderRequest {

    String clientOrderId;
    String figi;
    OrderSide side;
    OrderType type;
    long lots;
    BigDecimal limitPrice;
    String strategyId;
}