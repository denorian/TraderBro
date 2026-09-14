package com.traderbro.core.risk;

import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;

/**
 * Blocks opening new futures positions within {@code noNewPositionsDaysBeforeExpiry} days of
 * the contract's expiration, to avoid holding a decaying contract. Exits (SELL) are allowed.
 */
@RequiredArgsConstructor
public class FuturesExpiryLockRule implements RiskRule {

    private final RiskConfig config;

    @Override
    public int priority() {
        return 350;
    }

    @Override
    public String name() {
        return "FuturesExpiryLock";
    }

    @Override
    public RiskDecision check(RiskContext ctx) {
        if (!ctx.isFuture() || ctx.instrument().getFutureSpec() == null) {
            return RiskDecision.allow();
        }
        LocalDate expiration = ctx.instrument().getFutureSpec().getExpirationDate();
        if (expiration == null) {
            return RiskDecision.allow();
        }
        LocalDate today = ctx.now().atZone(config.getTradingZone()).toLocalDate();
        long daysToExpiry = java.time.temporal.ChronoUnit.DAYS.between(today, expiration);
        if (daysToExpiry <= config.getNoNewPositionsDaysBeforeExpiry()) {
            return RiskDecision.deny(name(), "future " + ctx.figi() + " expires in "
                    + daysToExpiry + " days (<= " + config.getNoNewPositionsDaysBeforeExpiry()
                    + "); new positions locked");
        }
        return RiskDecision.allow();
    }
}