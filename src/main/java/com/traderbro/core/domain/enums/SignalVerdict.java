package com.traderbro.core.domain.enums;

/** Verdict of a computed trading signal after passing through strategy and risk filters. */
public enum SignalVerdict {
    /** Signal accepted and eligible for order submission. */
    ACCEPTED,
    /** Signal suppressed by a {@code SignalFilter} (e.g. an LLM veto in future stages). */
    FILTERED,
    /** Signal suppressed by a risk rule; {@code reason} carries the rejecting rule. */
    REJECTED
}