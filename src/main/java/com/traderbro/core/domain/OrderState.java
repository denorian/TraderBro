package com.traderbro.core.domain;

import com.traderbro.core.domain.enums.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Builder;
import lombok.Value;

/** Broker-reported state of an order, used by reconciliation and timeout handling. */
@Value
@Builder
public class OrderState {

    String brokerOrderId;
    String figi;
    OrderStatus status;
    long filledLots;
    BigDecimal avgFillPrice;
    Instant updatedAt;
}