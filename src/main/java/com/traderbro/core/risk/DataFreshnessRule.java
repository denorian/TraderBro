package com.traderbro.core.risk;

import java.time.Duration;
import lombok.RequiredArgsConstructor;

/**
 * Blocks trading when the live data stream is stale (lag above the configured threshold),
 * preventing decisions on outdated quotes.
 */
@RequiredArgsConstructor
public class DataFreshnessRule implements RiskRule {

    private final RiskConfig config;

    @Override
    public int priority() {
        return 700;
    }

    @Override
    public String name() {
        return "DataFreshness";
    }

    @Override
    public RiskDecision check(RiskContext ctx) {
        if (ctx.streamLag() == null) {
            return RiskDecision.deny(name(), "stream lag unknown");
        }
        if (ctx.streamLag().compareTo(config.getMaxStreamLag()) > 0) {
            return RiskDecision.deny(name(), "stream lag " + ctx.streamLag()
                    + " > max " + config.getMaxStreamLag());
        }
        return RiskDecision.allow();
    }
}