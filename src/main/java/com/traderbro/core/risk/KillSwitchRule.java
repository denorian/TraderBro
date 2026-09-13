package com.traderbro.core.risk;

/** Rejects all orders while the kill-switch is active. */
public class KillSwitchRule implements RiskRule {

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public String name() {
        return "KillSwitch";
    }

    @Override
    public RiskDecision check(RiskContext ctx) {
        return ctx.killSwitchActive()
                ? RiskDecision.deny(name(), "kill-switch is active; new orders prohibited")
                : RiskDecision.allow();
    }
}