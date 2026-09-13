package com.traderbro.core.risk;

/**
 * A single risk check evaluated by the {@link RiskGate} before every order.
 * Rules are pure functions of a {@link RiskContext}; they never call a broker.
 */
public interface RiskRule {

    /** Ordering priority: lower value runs first. */
    int priority();

    /** Stable rule name used in audit events and the rejecting rule of a {@link RiskDecision}. */
    String name();

    /** Evaluates the rule. Returns an allow/deny decision. */
    RiskDecision check(RiskContext ctx);
}