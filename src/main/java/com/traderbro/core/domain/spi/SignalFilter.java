package com.traderbro.core.domain.spi;

import com.traderbro.core.domain.enums.Direction;

/**
 * External filter applied to a strategy's signal before it is accepted for execution.
 * <p>Stage 1 ships {@code NoOpSignalFilter}, which always returns 1.0 (accept). This is the
 * architectural seam for future LLM layers (stages 2-3): a filter may veto or up/down-weight
 * signals without touching the strategy engine.
 */
public interface SignalFilter {

    /**
     * Returns a weight in [0,1]. A weight strictly below 1.0 downgrades the signal;
     * a weight of 0.0 vetoes it (verdict {@code FILTERED}).
     */
    double weight(String figi, Direction dir);
}