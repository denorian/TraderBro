package com.traderbro.core.backtest;

import com.traderbro.core.domain.Instrument;
import com.traderbro.core.strategies.StrategyParams;
import com.traderbro.core.strategies.TradingStrategy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseTradingRecord;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.num.DecimalNum;

/**
 * Deterministic long-only backtester.
 *
 * <p>Signal rules are evaluated on <em>closed</em> bars; a transaction triggered on bar
 * {@code i-1} is executed at the <em>open</em> of bar {@code i} with slippage applied. This
 * ordering is what keeps the simulation free of look-ahead. Commission is charged per side.
 */
public class BacktestRunner {

    private final int moneyScale;

    public BacktestRunner(int moneyScale) {
        this.moneyScale = moneyScale;
    }

    /**
     * Runs {@code strategy} over {@code series} (built with {@link DecimalNum}).
     *
     * @param commissionPct  per-side commission as a fraction, e.g. 0.0005
     * @param slippageBps    slippage in basis points applied to execution price
     * @param initialCapital starting equity
     * @param maxPositionPct fraction of current equity per position (0..1]
     */
    public BacktestMetrics run(TradingStrategy strategy, BarSeries series,
                               StrategyParams params, Instrument instrument,
                               BigDecimal commissionPct, BigDecimal slippageBps,
                               BigDecimal initialCapital, BigDecimal maxPositionPct) {
        return runDetailed(strategy, series, params, instrument, commissionPct, slippageBps,
                initialCapital, maxPositionPct).metrics();
    }

    /** Like {@link #run} but also returns the closed trades (useful for tests/audits). */
    public BacktestResult runDetailed(TradingStrategy strategy, BarSeries series,
                                      StrategyParams params, Instrument instrument,
                                      BigDecimal commissionPct, BigDecimal slippageBps,
                                      BigDecimal initialCapital, BigDecimal maxPositionPct) {
        org.ta4j.core.Strategy ta4jStrategy = strategy.build(series, params);
        TradingRecord record = new BaseTradingRecord();

        double equity = initialCapital.doubleValue();
        double qty = 0.0;
        BacktestTrade open = null;
        List<BacktestTrade> trades = new ArrayList<>();
        List<Double> equityCurve = new ArrayList<>();

        int end = series.getEndIndex();
        for (int i = 0; i <= end; i++) {
            if (i > 0) {
                boolean inPos = open != null;
                boolean exitSig = inPos && ta4jStrategy.shouldExit(i - 1, record);
                boolean entrySig = !inPos && ta4jStrategy.shouldEnter(i - 1, record);
                double openPrice = execPrice(series.getBar(i).getOpenPrice().doubleValue(), slippageBps, false);
                if (exitSig && inPos) {
                    double proceeds = qty * openPrice;
                    double fee = proceeds * commissionPct.doubleValue();
                    equity += proceeds - fee;
                    record.operate(i, DecimalNum.valueOf(openPrice));
                    trades.add(new BacktestTrade(open.entryBar(), open.entryEquity(), equity,
                            (long) i - open.entryBar()));
                    open = null;
                    qty = 0.0;
                } else if (entrySig && !inPos) {
                    double budget = equity * maxPositionPct.doubleValue();
                    double notionalPerLot = openPrice * instrument.getLot();
                    long lots = (long) Math.floor(budget / notionalPerLot);
                    if (lots > 0) {
                        qty = lots * instrument.getLot();
                        double cost = qty * openPrice;
                        double fee = cost * commissionPct.doubleValue();
                        equity -= cost + fee;
                        record.operate(i, DecimalNum.valueOf(openPrice));
                        open = new BacktestTrade(i, equity, 0.0, 0);
                    }
                }
            }
            equityCurve.add(equity + qty * series.getBar(i).getClosePrice().doubleValue());
        }
        // Mark the residual position to market at the last close (no extra commission).
        if (open != null) {
            double lastClose = series.getLastBar().getClosePrice().doubleValue();
            equity += qty * lastClose;
            trades.add(new BacktestTrade(open.entryBar(), open.entryEquity(), equity,
                    (long) end - open.entryBar()));
        }

        double buyAndHold = buyAndHoldReturn(series, initialCapital, commissionPct, slippageBps);
        BacktestMetrics metrics = BacktestMetrics.fromRun(equityCurve, equity, initialCapital.doubleValue(),
                buyAndHold, trades, series, moneyScale);
        return new BacktestResult(metrics, List.copyOf(trades));
    }

    /** Backtest outcome: metrics plus the closed trades for inspection. */
    public record BacktestResult(BacktestMetrics metrics, List<BacktestTrade> trades) {
    }

    private double execPrice(double raw, BigDecimal slippageBps, boolean isSell) {
        double slip = raw * slippageBps.doubleValue() / 10_000.0;
        return isSell ? raw - slip : raw + slip;
    }

    /**
     * Backtests a futures instrument. Costs: commission per contract (rub) plus slippage in
     * price ticks. P&L per contract is {@code (exit - entry) * pointValue}. Equity is not
     * reduced by notional on open (margin is not locked), so the futures leverage is captured.
     */
    public BacktestResult runFuturesDetailed(TradingStrategy strategy, BarSeries series,
                                             StrategyParams params, Instrument instrument,
                                             BigDecimal feePerContract, int slippageTicks,
                                             BigDecimal initialCapital, BigDecimal maxPositionPct) {
        com.traderbro.core.domain.FutureSpec spec = instrument.getFutureSpec();
        if (spec == null) {
            throw new IllegalArgumentException("instrument is not a futures contract");
        }
        double pointValue = spec.pointValue().doubleValue();
        double tick = spec.getMinPriceIncrement().doubleValue();

        org.ta4j.core.Strategy ta4jStrategy = strategy.build(series, params);
        TradingRecord record = new BaseTradingRecord();

        double equity = initialCapital.doubleValue();
        int contracts = 0;
        double entryPrice = 0;
        BacktestTrade open = null;
        List<BacktestTrade> trades = new ArrayList<>();
        List<Double> equityCurve = new ArrayList<>();

        int end = series.getEndIndex();
        for (int i = 0; i <= end; i++) {
            if (i > 0) {
                boolean inPos = contracts > 0;
                boolean exitSig = inPos && ta4jStrategy.shouldExit(i - 1, record);
                boolean entrySig = !inPos && ta4jStrategy.shouldEnter(i - 1, record);
                double raw = series.getBar(i).getOpenPrice().doubleValue();
                if (exitSig && inPos) {
                    double exitPrice = raw - tick * slippageTicks;
                    double pnl = (exitPrice - entryPrice) * pointValue * contracts;
                    double fee = contracts * feePerContract.doubleValue();
                    equity += pnl - fee;
                    record.operate(i, DecimalNum.valueOf(exitPrice));
                    trades.add(new BacktestTrade(open.entryBar(), open.entryEquity(), equity,
                            (long) i - open.entryBar()));
                    contracts = 0;
                } else if (entrySig && !inPos) {
                    double exec = raw + tick * slippageTicks;
                    double notionalPerContract = exec * instrument.getLot();
                    int c = (int) Math.floor(equity * maxPositionPct.doubleValue() / notionalPerContract);
                    if (c > 0) {
                        contracts = c;
                        entryPrice = exec;
                        equity -= contracts * feePerContract.doubleValue();
                        record.operate(i, DecimalNum.valueOf(exec));
                        open = new BacktestTrade(i, equity, 0.0, 0);
                    }
                }
            }
            double close = series.getBar(i).getClosePrice().doubleValue();
            double mark = contracts > 0
                    ? equity + (close - entryPrice) * pointValue * contracts
                    : equity;
            equityCurve.add(mark);
        }
        if (contracts > 0) {
            double lastClose = series.getLastBar().getClosePrice().doubleValue();
            double pnl = (lastClose - entryPrice) * pointValue * contracts;
            equity += pnl;
            trades.add(new BacktestTrade(open.entryBar(), open.entryEquity(), equity,
                    (long) end - open.entryBar()));
        }

        double buyAndHold = buyAndHoldReturnFutures(series, initialCapital, feePerContract, slippageTicks, spec);
        BacktestMetrics metrics = BacktestMetrics.fromRun(equityCurve, equity, initialCapital.doubleValue(),
                buyAndHold, trades, series, moneyScale);
        return new BacktestResult(metrics, List.copyOf(trades));
    }

    private double buyAndHoldReturnFutures(BarSeries series, BigDecimal capital,
                                           BigDecimal feePerContract, int slippageTicks,
                                           com.traderbro.core.domain.FutureSpec spec) {
        if (series.getBarCount() < 2) {
            return 0.0;
        }
        double pointValue = spec.pointValue().doubleValue();
        double tick = spec.getMinPriceIncrement().doubleValue();
        double buy = series.getBar(0).getOpenPrice().doubleValue() + tick * slippageTicks;
        double sell = series.getLastBar().getClosePrice().doubleValue() - tick * slippageTicks;
        double notionalPerContract = buy * spec.getLot();
        int c = (int) Math.floor(capital.doubleValue() / notionalPerContract);
        if (c <= 0) {
            return 0.0;
        }
        double pnl = (sell - buy) * pointValue * c;
        double fees = 2.0 * c * feePerContract.doubleValue();
        double net = capital.doubleValue() + pnl - fees;
        return net / capital.doubleValue() - 1.0;
    }

    private double buyAndHoldReturn(BarSeries series, BigDecimal capital,
                                    BigDecimal commissionPct, BigDecimal slippageBps) {
        if (series.getBarCount() < 2) {
            return 0.0;
        }
        double buy = execPrice(series.getBar(0).getOpenPrice().doubleValue(), slippageBps, false);
        double sell = execPrice(series.getLastBar().getClosePrice().doubleValue(), slippageBps, true);
        double qty = capital.doubleValue() * (1 - commissionPct.doubleValue()) / buy;
        double net = qty * sell * (1 - commissionPct.doubleValue());
        return net / capital.doubleValue() - 1.0;
    }
}