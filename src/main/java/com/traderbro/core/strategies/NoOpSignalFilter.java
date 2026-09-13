package com.traderbro.core.strategies;

import com.traderbro.core.domain.enums.Direction;
import com.traderbro.core.domain.spi.SignalFilter;

/**
 * Stage-1 {@link SignalFilter}: always accepts (weight 1.0). This is the seam where an
 * external decision layer (LLM in stages 2-3) will be attached without touching the engine.
 */
public class NoOpSignalFilter implements SignalFilter {

    @Override
    public double weight(String figi, Direction dir) {
        return 1.0;
    }
}