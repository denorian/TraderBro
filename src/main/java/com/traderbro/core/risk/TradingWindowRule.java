package com.traderbro.core.risk;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import lombok.RequiredArgsConstructor;

/**
 * Restricts trading to the configured daily window (Europe/Moscow) and blocks weekend
 * trading unless explicitly enabled.
 */
@RequiredArgsConstructor
public class TradingWindowRule implements RiskRule {

    private final RiskConfig config;

    @Override
    public int priority() {
        return 600;
    }

    @Override
    public String name() {
        return "TradingWindow";
    }

    @Override
    public RiskDecision check(RiskContext ctx) {
        ZonedDateTime now = ctx.now().atZone(config.getTradingZone());
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            if (!config.isAllowWeekendTrading()) {
                return RiskDecision.deny(name(), "weekend trading disabled (" + day + ")");
            }
            return RiskDecision.allow();
        }
        LocalTime t = now.toLocalTime();
        LocalTime start = config.getTradingWindowStart();
        LocalTime end = config.getTradingWindowEnd();
        boolean inWindow = !t.isBefore(start) && t.isBefore(end);
        if (!inWindow) {
            return RiskDecision.deny(name(), "outside trading window " + start + "-" + end);
        }
        return RiskDecision.allow();
    }
}