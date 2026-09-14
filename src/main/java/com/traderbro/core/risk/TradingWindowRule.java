package com.traderbro.core.risk;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;

/**
 * Restricts trading to the configured daily window and blocks weekend trading unless enabled.
 * Shares use the single main session; futures use their own window list (evening session +
 * clearing breaks), as configured in {@link RiskConfig#getFuturesTradingWindows()}.
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
        if (ctx.isFuture()) {
            List<RiskConfig.TradingWindow> windows = config.getFuturesTradingWindows();
            if (windows != null && windows.stream().anyMatch(w -> w.contains(t))) {
                return RiskDecision.allow();
            }
            return RiskDecision.deny(name(), "outside futures trading windows: " + windows);
        }
        LocalTime start = config.getTradingWindowStart();
        LocalTime end = config.getTradingWindowEnd();
        boolean inWindow = !t.isBefore(start) && t.isBefore(end);
        if (!inWindow) {
            return RiskDecision.deny(name(), "outside trading window " + start + "-" + end);
        }
        return RiskDecision.allow();
    }
}