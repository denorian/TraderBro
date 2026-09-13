package com.traderbro.core.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;

/**
 * Ensures total exposure across all instruments, after applying this order, stays within the
 * configured limit (fraction of portfolio value).
 */
@RequiredArgsConstructor
public class ExposureLimitRule implements RiskRule {

    private final RiskConfig config;

    @Override
    public int priority() {
        return 400;
    }

    @Override
    public String name() {
        return "ExposureLimit";
    }

    @Override
    public RiskDecision check(RiskContext ctx) {
        BigDecimal total = ctx.portfolio().getTotalValue();
        if (total == null || total.signum() <= 0) {
            return RiskDecision.deny(name(), "portfolio valuation unavailable");
        }
        BigDecimal notional = ctx.price().multiply(BigDecimal.valueOf(ctx.lots()))
                .multiply(BigDecimal.valueOf(ctx.instrument().getLot()))
                .setScale(config.getMoneyScale(), RoundingMode.HALF_UP);
        BigDecimal after = (ctx.totalExposure() == null ? BigDecimal.ZERO : ctx.totalExposure()).add(notional);
        BigDecimal limit = total.multiply(config.getExposureLimitPct());
        if (after.compareTo(limit) > 0) {
            return RiskDecision.deny(name(), "total exposure after order " + after + " > limit " + limit);
        }
        return RiskDecision.allow();
    }
}