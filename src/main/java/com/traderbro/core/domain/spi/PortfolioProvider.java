package com.traderbro.core.domain.spi;

import com.traderbro.core.domain.Portfolio;
import com.traderbro.core.domain.Position;
import java.util.List;

/**
 * Supplies the current portfolio valuation and open positions to the execution layer,
 * used to build a {@code RiskContext}. Implemented by a service combining broker positions
 * with persisted state.
 */
public interface PortfolioProvider {

    Portfolio snapshot();

    List<Position> positions();
}