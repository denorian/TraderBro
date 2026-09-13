package com.traderbro.core.domain.spi;

import java.util.Map;

/**
 * Internal source of truth for expected open positions, derived from persisted trades.
 * Used by reconciliation to detect divergence from the broker.
 */
public interface PositionLedger {

    /** figi -> signed shares expected from our own trade history. */
    Map<String, Long> expectedShares();
}