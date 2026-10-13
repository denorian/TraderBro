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
                    record.operate(i, DecimalNum.valueOf(openPrice), DecimalNum.valueOf(qty));
                    trades.add(new BacktestTrade(open.getEntryBar(), open.getEntryEquity(), equity,
                            (long) i - open.getEntryBar()));
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
                        record.operate(i, DecimalNum.valueOf(openPrice), DecimalNum.valueOf(qty));
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
            trades.add(new BacktestTrade(open.getEntryBar(), open.getEntryEquity(), equity,
                    (long) end - open.getEntryBar()));
        }

        double buyAndHold = buyAndHoldReturn(series, initialCapital, commissionPct, slippageBps);
        BacktestMetrics metrics = BacktestMetrics.fromRun(equityCurve, equity, initialCapital.doubleValue(),
                buyAndHold, trades, series, moneyScale);
        return new BacktestResult(metrics, List.copyOf(trades));
    }

    /** Backtest outcome: metrics plus the closed trades for inspection. */
    public record BacktestResult(BacktestMetrics metrics, List<BacktestTrade> trades) {
    }

    /**
     * Detailed long-only simulation over shares, capturing full trade records, ENTRY/EXIT
     * signal points and a per-bar equity curve. Same execution model as {@link #runDetailed}
     * (signals on closed bar {@code i-1}, execution at open of bar {@code i}).
     */
    public SimulationResult simulateShares(TradingStrategy strategy, BarSeries series,
                                           StrategyParams params, Instrument instrument,
                                           BigDecimal commissionPct, BigDecimal slippageBps,
                                           BigDecimal initialCapital, BigDecimal maxPositionPct) {
        org.ta4j.core.Strategy ta4jStrategy = strategy.build(series, params);
        TradingRecord record = new BaseTradingRecord();

        double equity = initialCapital.doubleValue();
        double qty = 0.0;
        int entryBar = -1;
        double entryPrice = 0.0;
        double entryEquity = 0.0;
        java.util.ArrayList<TradeRecord> trades = new java.util.ArrayList<>();
        java.util.ArrayList<BacktestTrade> btTrades = new java.util.ArrayList<>();
        java.util.ArrayList<SignalRecord> signals = new java.util.ArrayList<>();
        java.util.ArrayList<EquityPoint> equityCurve = new java.util.ArrayList<>();

        int end = series.getEndIndex();
        for (int i = 0; i <= end; i++) {
            if (i > 0) {
                boolean inPos = qty > 0;
                boolean exitSig = inPos && ta4jStrategy.shouldExit(i - 1, record);
                boolean entrySig = !inPos && ta4jStrategy.shouldEnter(i - 1, record);
                double openPrice = execPrice(series.getBar(i).getOpenPrice().doubleValue(), slippageBps, false);
                if (exitSig && inPos) {
                    double proceeds = qty * openPrice;
                    double fee = proceeds * commissionPct.doubleValue();
                    equity += proceeds - fee;
                    record.operate(i, DecimalNum.valueOf(openPrice), DecimalNum.valueOf(qty));
                    trades.add(toTrade(strategy, instrument, series, entryBar, i, entryPrice,
                            openPrice, qty, equity - entryEquity, equity, "STRATEGY_EXIT"));
                    btTrades.add(new BacktestTrade(entryBar, entryEquity, equity, i - entryBar));
                    signals.add(new SignalRecord(strategy.id(), instrument.getFigi(),
                            series.getBar(i).getEndTime(), SignalRecord.SignalType.EXIT, openPrice,
                            "ACCEPTED", "exit"));
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
                        record.operate(i, DecimalNum.valueOf(openPrice), DecimalNum.valueOf(qty));
                        entryBar = i;
                        entryPrice = openPrice;
                        entryEquity = equity;
                        signals.add(new SignalRecord(strategy.id(), instrument.getFigi(),
                                series.getBar(i).getEndTime(), SignalRecord.SignalType.ENTRY, openPrice,
                                "ACCEPTED", "entry"));
                    }
                }
            }
            double close = series.getBar(i).getClosePrice().doubleValue();
            equityCurve.add(new EquityPoint(series.getBar(i).getEndTime(), equity + qty * close));
        }
        // Mark the residual position to market at the last close (no extra commission).
        if (qty > 0) {
            double lastClose = series.getLastBar().getClosePrice().doubleValue();
            equity += qty * lastClose;
            trades.add(toTrade(strategy, instrument, series, entryBar, end, entryPrice, lastClose,
                    qty, equity - entryEquity, equity, "END_OF_DATA"));
            btTrades.add(new BacktestTrade(entryBar, entryEquity, equity, end - entryBar));
            signals.add(new SignalRecord(strategy.id(), instrument.getFigi(),
                    series.getLastBar().getEndTime(), SignalRecord.SignalType.EXIT, lastClose,
                    "ACCEPTED", "end_of_data"));
        }

        double buyAndHold = buyAndHoldReturn(series, initialCapital, commissionPct, slippageBps);
        BacktestMetrics metrics = BacktestMetrics.fromRun(
                equityCurve.stream().map(EquityPoint::equity).toList(), equity,
                initialCapital.doubleValue(), buyAndHold, btTrades, series, moneyScale);
        return new SimulationResult(metrics, List.copyOf(trades), List.copyOf(signals),
                List.copyOf(equityCurve));
    }

    /**
     * Detailed long-only simulation over futures (margin-based, no notional lock). P&L per
     * contract is {@code (exit - entry) * pointValue}; commission per contract.
     */
    public SimulationResult simulateFutures(TradingStrategy strategy, BarSeries series,
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
        double entryEquity = 0;
        int entryBar = -1;
        java.util.ArrayList<TradeRecord> trades = new java.util.ArrayList<>();
        java.util.ArrayList<BacktestTrade> btTrades = new java.util.ArrayList<>();
        java.util.ArrayList<SignalRecord> signals = new java.util.ArrayList<>();
        java.util.ArrayList<EquityPoint> equityCurve = new java.util.ArrayList<>();

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
                    record.operate(i, DecimalNum.valueOf(exitPrice), DecimalNum.valueOf(contracts));
                    trades.add(toTrade(strategy, instrument, series, entryBar, i, entryPrice,
                            exitPrice, contracts, pnl - fee, equity, "STRATEGY_EXIT"));
                    btTrades.add(new BacktestTrade(entryBar, entryEquity, equity, i - entryBar));
                    signals.add(new SignalRecord(strategy.id(), instrument.getFigi(),
                            series.getBar(i).getEndTime(), SignalRecord.SignalType.EXIT, exitPrice,
                            "ACCEPTED", "exit"));
                    contracts = 0;
                } else if (entrySig && !inPos) {
                    double exec = raw + tick * slippageTicks;
                    double notionalPerContract = exec * instrument.getLot();
                    int c = (int) Math.floor(equity * maxPositionPct.doubleValue() / notionalPerContract);
                    if (c > 0) {
                        contracts = c;
                        entryPrice = exec;
                        entryEquity = equity - contracts * feePerContract.doubleValue();
                        equity = entryEquity;
                        record.operate(i, DecimalNum.valueOf(exec), DecimalNum.valueOf(contracts));
                        entryBar = i;
                        signals.add(new SignalRecord(strategy.id(), instrument.getFigi(),
                                series.getBar(i).getEndTime(), SignalRecord.SignalType.ENTRY, exec,
                                "ACCEPTED", "entry"));
                    }
                }
            }
            double close = series.getBar(i).getClosePrice().doubleValue();
            double mark = contracts > 0
                    ? equity + (close - entryPrice) * pointValue * contracts
                    : equity;
            equityCurve.add(new EquityPoint(series.getBar(i).getEndTime(), mark));
        }
        if (contracts > 0) {
            double lastClose = series.getLastBar().getClosePrice().doubleValue();
            double pnl = (lastClose - entryPrice) * pointValue * contracts;
            equity += pnl;
            trades.add(toTrade(strategy, instrument, series, entryBar, end, entryPrice, lastClose,
                    contracts, pnl, equity, "END_OF_DATA"));
            btTrades.add(new BacktestTrade(entryBar, entryEquity, equity, end - entryBar));
            signals.add(new SignalRecord(strategy.id(), instrument.getFigi(),
                    series.getLastBar().getEndTime(), SignalRecord.SignalType.EXIT, lastClose,
                    "ACCEPTED", "end_of_data"));
        }

        double buyAndHold = buyAndHoldReturnFutures(series, initialCapital, feePerContract, slippageTicks, spec);
        BacktestMetrics metrics = BacktestMetrics.fromRun(
                equityCurve.stream().map(EquityPoint::equity).toList(), equity,
                initialCapital.doubleValue(), buyAndHold, btTrades, series, moneyScale);
        return new SimulationResult(metrics, List.copyOf(trades), List.copyOf(signals),
                List.copyOf(equityCurve));
    }

    private TradeRecord toTrade(TradingStrategy strategy, Instrument instrument, BarSeries series,
                                int entryBar, int exitBar, double entryPrice, double exitPrice,
                                double qty, double pnl, double equity, String exitReason) {
        double pnlPct = entryPrice > 0 ? pnl / (entryPrice * qty) : 0.0;
        return TradeRecord.builder()
                .strategyId(strategy.id())
                .figi(instrument.getFigi())
                .direction(com.traderbro.core.domain.enums.Direction.LONG)
                .entryTs(series.getBar(entryBar).getEndTime())
                .entryPrice(entryPrice)
                .exitTs(series.getBar(exitBar).getEndTime())
                .exitPrice(exitPrice)
                .quantity(qty)
                .pnl(pnl)
                .pnlPct(pnlPct)
                .exitReason(exitReason)
                .build();
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
                    record.operate(i, DecimalNum.valueOf(exitPrice), DecimalNum.valueOf(contracts));
                    trades.add(new BacktestTrade(open.getEntryBar(), open.getEntryEquity(), equity,
                            (long) i - open.getEntryBar()));
                    contracts = 0;
                } else if (entrySig && !inPos) {
                    double exec = raw + tick * slippageTicks;
                    double notionalPerContract = exec * instrument.getLot();
                    int c = (int) Math.floor(equity * maxPositionPct.doubleValue() / notionalPerContract);
                    if (c > 0) {
                        contracts = c;
                        entryPrice = exec;
                        equity -= contracts * feePerContract.doubleValue();
                        record.operate(i, DecimalNum.valueOf(exec), DecimalNum.valueOf(contracts));
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
            trades.add(new BacktestTrade(open.getEntryBar(), open.getEntryEquity(), equity,
                    (long) end - open.getEntryBar()));
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