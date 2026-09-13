package com.traderbro.core.domain;

import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.domain.enums.OrderType;
import java.math.BigDecimal;
import java.util.Map;
import lombok.Builder;
import lombok.Value;

/**
 * Runtime configuration of a single strategy, read from the {@code strategy_config} table.
 * Supports hot reload (registry rereads periodically) without restart.
 */
@Value
@Builder
public class StrategyConfig {

    String id;
    boolean enabled;
    /** Free-form JSONB parameters consumed by the strategy. */
    Map<String, String> params;
    OrderType orderType;
    /** Max position size as a fraction of portfolio value for this strategy's signals. */
    BigDecimal maxPositionPct;
    CandleInterval interval;
    /** Version/row version for optimistic reload detection. */
    long version;
}