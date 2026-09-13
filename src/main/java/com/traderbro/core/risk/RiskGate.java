package com.traderbro.core.risk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs all registered {@link RiskRule}s in priority order before an order is submitted.
 * The first denial short-circuits evaluation and is reported to the audit sink.
 * <p>This class is pure logic: it never calls a broker and holds no mutable trading state.
 */
@Slf4j
public class RiskGate {

    private final List<RiskRule> rules;
    private final Consumer<String> auditSink;

    public RiskGate(List<RiskRule> rules, Consumer<String> auditSink) {
        List<RiskRule> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparingInt(RiskRule::priority));
        this.rules = List.copyOf(sorted);
        this.auditSink = auditSink;
    }

    /** Evaluates all rules for the given context. */
    public RiskDecision evaluate(RiskContext ctx) {
        for (RiskRule rule : rules) {
            RiskDecision decision;
            try {
                decision = rule.check(ctx);
            } catch (RuntimeException e) {
                log.error("Risk rule {} threw; denying order for safety", rule.name(), e);
                decision = RiskDecision.deny(rule.name(), "rule error: " + e.getMessage());
            }
            if (!decision.isAllowed()) {
                auditSink.accept("riskgate: DENIED by " + decision.getRule()
                        + " for figi=" + ctx.figi() + " side=" + ctx.side()
                        + " lots=" + ctx.lots() + " reason=" + decision.getReason());
                return decision;
            }
        }
        return RiskDecision.allow();
    }

    /** Ordered list of registered rules (for introspection/tests). */
    public List<RiskRule> rules() {
        return rules;
    }
}