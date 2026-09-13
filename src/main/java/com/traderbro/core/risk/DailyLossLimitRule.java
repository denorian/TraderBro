package com.traderbro.core.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;

/**
 * Blocks trading when the current day's loss reaches the configured limit
 * (fraction of portfolio value).
 */
@RequiredArgsConstructor
public class DailyLossLimitRule implements RiskRule {

    private final RiskConfig config;

    @Override
    public int priority() {
        return 200;
    }

    @Override
    public String name() {
        return "DailyLossLimit";
    }

    @Override
    public RiskDecision check(RiskContext ctx) {
        BigDecimal total = ctx.portfolio().getTotalValue();
        BigDecimal dayPnL = ctx.portfolio().getDayPnL();
        if (total == null || total.signum() <= 0 || dayPnL == null) {
            return RiskDecision.deny(name(), "portfolio valuation unavailable for daily-loss check");
        }
        BigDecimal lossPct = dayPnL.divide(total, 6, RoundingMode.HALF_UP);
        if (lossPct.compareTo(config.getDailyLossLimitPct().negate()) <= 0) {
            return RiskDecision.deny(name(), "daily loss " + lossPct + " <= limit "
                    + config.getDailyLossLimitPct().negate());
        }
        return RiskDecision.allow();
    }
}