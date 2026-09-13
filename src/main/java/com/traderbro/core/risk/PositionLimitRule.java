package com.traderbro.core.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;

/**
 * Ensures the position in a single instrument, after applying this order, stays within the
 * configured limit (fraction of portfolio value).
 */
@RequiredArgsConstructor
public class PositionLimitRule implements RiskRule {

    private final RiskConfig config;

    @Override
    public int priority() {
        return 300;
    }

    @Override
    public String name() {
        return "PositionLimit";
    }

    @Override
    public RiskDecision check(RiskContext ctx) {
        BigDecimal total = ctx.portfolio().getTotalValue();
        if (total == null || total.signum() <= 0) {
            return RiskDecision.deny(name(), "portfolio valuation unavailable");
        }
        BigDecimal notional = notional(ctx);
        // For a buy, the position grows; for a sell (exit) it shrinks and cannot breach.
        BigDecimal after = ctx.instrumentPositionValue() == null
                ? notional
                : ctx.instrumentPositionValue().add(notional);
        BigDecimal limit = total.multiply(config.getPositionLimitPct());
        if (after.compareTo(limit) > 0) {
            return RiskDecision.deny(name(), "position after order " + after + " > limit " + limit);
        }
        return RiskDecision.allow();
    }

    private BigDecimal notional(RiskContext ctx) {
        BigDecimal raw = ctx.price().multiply(BigDecimal.valueOf(ctx.lots()))
                .multiply(BigDecimal.valueOf(ctx.instrument().getLot()));
        return raw.setScale(config.getMoneyScale(), RoundingMode.HALF_UP);
    }
}