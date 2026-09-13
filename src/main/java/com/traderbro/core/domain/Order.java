package com.traderbro.core.domain;

import com.traderbro.core.domain.enums.OrderSide;
import com.traderbro.core.domain.enums.OrderStatus;
import com.traderbro.core.domain.enums.OrderType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import lombok.With;

/**
 * Domain representation of an order. {@code id} is the client-generated UUID used for
 * idempotency/deduplication — it is created and persisted <em>before</em> submission.
 */
@Value
@Builder
@With
public class Order {

    UUID id;
    String figi;
    OrderSide side;
    OrderType type;
    long requestedLots;
    BigDecimal limitPrice;
    OrderStatus status;
    /** Broker-side order id, assigned after submission. */
    String brokerOrderId;
    /** Lots actually filled so far. */
    long filledLots;
    BigDecimal avgFillPrice;
    Instant createdAt;
    Instant updatedAt;
    String strategyId;
    String reason;
}