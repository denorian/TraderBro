package com.traderbro.core.strategies;

import com.traderbro.core.domain.FutureSpec;
import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.Portfolio;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.Value;

/**
 * Sizes futures positions by risk: number of contracts is derived from the risk capital
 * allocated to the trade and the per-point risk of the instrument.
 * <p>contracts = floor(riskCapital / (stopDistanceInPoints &times; pointValue)), where the
 * stop distance is measured in price points. The result is then capped so that the total
 * futures margin (current + new) stays within {@code marginLimitPct} of the portfolio.
 */
public class FuturesPositionSizer {

    @Value
    public static class SizingResult {
        int contracts;
        BigDecimal notional;
        /** New contracts' GO (initial margin). */
        BigDecimal newMargin;
        BigDecimal effectivePct;
        /** True when the margin cap forced the size down to zero. */
        boolean marginLimitHit;
    }

    private final int moneyScale;

    public FuturesPositionSizer(int moneyScale) {
        this.moneyScale = moneyScale;
    }

    /**
     * @param riskCapitalPct     fraction of portfolio allocated to this trade (default 0.01)
     * @param instrument         the futures instrument (must have a {@link FutureSpec})
     * @param entryPrice         planned entry price
     * @param stopPrice          stop level (used for risk distance); for shorts &gt; entry
     * @param currentFuturesMargin total GO of already-open futures positions, rub
     * @param marginLimitPct     max total margin as a fraction of portfolio
     * @param portfolio          current portfolio valuation
     */
    public SizingResult size(BigDecimal riskCapitalPct, Instrument instrument,
                             BigDecimal entryPrice, BigDecimal stopPrice,
                             BigDecimal currentFuturesMargin, BigDecimal marginLimitPct,
                             Portfolio portfolio) {
        if (!instrument.isFuture() || instrument.getFutureSpec() == null) {
            throw new IllegalArgumentException("instrument " + instrument.getTicker() + " is not a futures contract");
        }
        FutureSpec spec = instrument.getFutureSpec();
        if (entryPrice == null || entryPrice.signum() <= 0 || stopPrice == null) {
            throw new IllegalArgumentException("entry/stop prices must be valid");
        }
        if (spec.getInitialMargin() == null || spec.getInitialMargin().signum() <= 0) {
            throw new IllegalArgumentException("future has no initial margin (GO)");
        }

        BigDecimal riskCapital = portfolio.getTotalValue().multiply(riskCapitalPct);
        BigDecimal stopDistPts = entryPrice.subtract(stopPrice).abs()
                .divide(spec.getMinPriceIncrement(), 8, RoundingMode.HALF_UP);
        BigDecimal riskPerContract = stopDistPts.multiply(spec.pointValue());
        int contracts = riskPerContract.signum() > 0
                ? riskCapital.divide(riskPerContract, 0, RoundingMode.DOWN).intValue()
                : 0;

        // Cap by margin (GO).
        BigDecimal marginPerContract = spec.getInitialMargin();
        BigDecimal marginBudget = portfolio.getTotalValue().multiply(marginLimitPct)
                .subtract(currentFuturesMargin == null ? BigDecimal.ZERO : currentFuturesMargin);
        int marginContracts = marginBudget.signum() > 0
                ? marginBudget.divide(marginPerContract, 0, RoundingMode.DOWN).intValue()
                : 0;
        contracts = Math.min(contracts, marginContracts);
        boolean marginHit = contracts == 0;

        BigDecimal newMargin = marginPerContract.multiply(BigDecimal.valueOf(contracts))
                .setScale(moneyScale, RoundingMode.HALF_UP);
        BigDecimal notional = entryPrice.multiply(BigDecimal.valueOf(spec.getLot()))
                .multiply(BigDecimal.valueOf(contracts)).setScale(moneyScale, RoundingMode.HALF_UP);
        BigDecimal effectivePct = portfolio.getTotalValue().signum() == 0
                ? BigDecimal.ZERO
                : notional.divide(portfolio.getTotalValue(), 6, RoundingMode.HALF_UP);

        return new SizingResult(contracts, notional, newMargin, effectivePct, marginHit);
    }
}