package com.traderbro.core.domain;

import com.traderbro.core.domain.enums.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/** A single executed trade (fill), the source of truth for the position ledger. */
@Value
@Builder
public class Trade {

    UUID id;
    UUID orderId;
    String figi;
    OrderSide side;
    long lots;
    BigDecimal price;
    BigDecimal commission;
    Instant ts;
}