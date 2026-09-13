package com.traderbro.core.risk;

import lombok.Value;

/** Outcome of running the risk gate for an order. */
@Value
public class RiskDecision {

    boolean allowed;
    /** Name of the rejecting rule, or null when allowed. */
    String rule;
    /** Human-readable reason, or null when allowed. */
    String reason;

    public static RiskDecision allow() {
        return new RiskDecision(true, null, null);
    }

    public static RiskDecision deny(String rule, String reason) {
        return new RiskDecision(false, rule, reason);
    }
}