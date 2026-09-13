package com.traderbro.core.strategies;

import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.Portfolio;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.Value;

/**
 * Computes the number of lots to buy for a signal: fixed fraction of portfolio value per
 * signal, rounded down to the instrument's lot size. All monetary math uses BigDecimal.
 */
public class PositionSizer {

    @Value
    public static class SizingResult {
        long lots;
        BigDecimal notional;
        /** Actual fraction of portfolio used after lot rounding. */
        BigDecimal effectivePct;
    }

    private final int moneyScale;

    public PositionSizer(int moneyScale) {
        this.moneyScale = moneyScale;
    }

    /**
     * Sizes a position.
     *
     * @param cfgMaxPct  max fraction of portfolio per signal (0..1], from strategy config
     * @param instrument instrument for lot size and price precision
     * @param price      execution price (approx, for notional)
     * @param portfolio  current portfolio valuation
     */
    public SizingResult size(BigDecimal cfgMaxPct, Instrument instrument,
                             BigDecimal price, Portfolio portfolio) {
        if (instrument.getLot() <= 0) {
            throw new IllegalArgumentException("instrument " + instrument.getTicker() + " has non-positive lot");
        }
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("price must be positive");
        }
        BigDecimal budget = portfolio.getTotalValue().multiply(cfgMaxPct);
        BigDecimal notionalPerLot = price.multiply(BigDecimal.valueOf(instrument.getLot()));
        if (notionalPerLot.signum() <= 0) {
            return new SizingResult(0, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        long lots = budget.divide(notionalPerLot, 0, RoundingMode.DOWN).longValue();
        BigDecimal notional = notionalPerLot.multiply(BigDecimal.valueOf(lots))
                .setScale(moneyScale, RoundingMode.HALF_UP);
        BigDecimal effectivePct = portfolio.getTotalValue().signum() == 0
                ? BigDecimal.ZERO
                : notional.divide(portfolio.getTotalValue(), 6, RoundingMode.HALF_UP);
        return new SizingResult(lots, notional, effectivePct);
    }
}