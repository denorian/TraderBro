package com.traderbro.core.risk;

import lombok.RequiredArgsConstructor;

/** Caps the rate of order submissions per minute. */
@RequiredArgsConstructor
public class OrderRateLimitRule implements RiskRule {

    private final RiskConfig config;

    @Override
    public int priority() {
        return 500;
    }

    @Override
    public String name() {
        return "OrderRateLimit";
    }

    @Override
    public RiskDecision check(RiskContext ctx) {
        if (ctx.ordersLastMinute() >= config.getMaxOrderRatePerMinute()) {
            return RiskDecision.deny(name(), "order rate " + ctx.ordersLastMinute()
                    + "/min reached limit " + config.getMaxOrderRatePerMinute());
        }
        return RiskDecision.allow();
    }
}