package com.traderbro.core.backtest;

import com.traderbro.core.indicators.SeriesFactory;
import java.time.Instant;
import java.util.List;
import org.ta4j.core.BarSeries;

/**
 * Performance metrics of a backtest run. Doubles are acceptable here: these are reporting
 * metrics only, never used for order sizing or money math (which stays on BigDecimal).
 */
public record BacktestMetrics(
        double totalReturn,
        double buyAndHoldReturn,
        double cagr,
        double sharpe,
        double sortino,
        double calmar,
        double maxDrawdown,
        double winRate,
        double profitFactor,
        long numTrades,
        double avgBarsHeld,
        double finalEquity,
        double initialEquity) {

    /**
     * Computes all metrics from a per-bar equity curve and the list of closed trades.
     * Returns are annualized from the actual bar timestamps (correct for daily and intraday).
     */
    public static BacktestMetrics fromRun(List<Double> equityCurve,
                                          double finalEquity, double initialEquity,
                                          double buyAndHoldReturn, List<BacktestTrade> trades,
                                          BarSeries series, int moneyScale) {
        int n = equityCurve.size();
        double totalReturn = n == 0 ? 0.0 : finalEquity / initialEquity - 1.0;

        Instant first = series.getBarCount() > 0
                ? series.getBar(0).getEndTime().toInstant()
                : Instant.now();
        Instant last = SeriesFactory.lastEndTime(series) != null
                ? SeriesFactory.lastEndTime(series)
                : first;
        double years = Math.max(daysBetween(first, last) / 365.25, 1.0 / 365.25);

        double cagr = years > 0 ? Math.pow(Math.max(finalEquity / initialEquity, 1e-12), 1.0 / years) - 1.0 : 0.0;

        double meanReturn = 0, variance = 0, downsideSq = 0;
        if (n > 1) {
            double[] returns = new double[n - 1];
            for (int i = 1; i < n; i++) {
                double prev = equityCurve.get(i - 1);
                returns[i - 1] = prev > 0 ? equityCurve.get(i) / prev - 1.0 : 0.0;
                meanReturn += returns[i - 1];
            }
            meanReturn /= returns.length;
            for (double r : returns) {
                variance += (r - meanReturn) * (r - meanReturn);
                double downside = Math.min(r, 0.0);
                downsideSq += downside * downside;
            }
            variance /= returns.length;
            downsideSq /= returns.length;
        }
        double std = Math.sqrt(variance);
        double downsideStd = Math.sqrt(downsideSq);
        double periodsPerYear = Math.max((double) Math.max(n - 1, 1) / years, 1.0);
        double sharpe = std > 1e-12 ? meanReturn / std * Math.sqrt(periodsPerYear) : 0.0;
        double sortino = downsideStd > 1e-12 ? meanReturn / downsideStd * Math.sqrt(periodsPerYear) : 0.0;

        double maxDrawdown = 0, peak = -Double.MAX_VALUE;
        for (double e : equityCurve) {
            peak = Math.max(peak, e);
            if (peak > 0) {
                maxDrawdown = Math.max(maxDrawdown, (peak - e) / peak);
            }
        }
        double calmar = maxDrawdown > 1e-12 ? cagr / maxDrawdown : 0.0;

        long wins = trades.stream().filter(BacktestTrade::isWin).count();
        double winRate = trades.isEmpty() ? 0.0 : (double) wins / trades.size();
        double grossProfit = 0, grossLoss = 0;
        long totalBars = 0;
        for (BacktestTrade t : trades) {
            double pnl = t.getExitEquity() - t.getEntryEquity();
            if (pnl >= 0) {
                grossProfit += pnl;
            } else {
                grossLoss += -pnl;
            }
            totalBars += t.getBarsHeld();
        }
        double profitFactor = grossLoss > 1e-12 ? grossProfit / grossLoss : (grossProfit > 0 ? Double.POSITIVE_INFINITY : 0.0);
        double avgBarsHeld = trades.isEmpty() ? 0.0 : (double) totalBars / trades.size();

        return new BacktestMetrics(totalReturn, buyAndHoldReturn, cagr, sharpe, sortino,
                calmar, maxDrawdown, winRate, profitFactor, trades.size(), avgBarsHeld,
                finalEquity, initialEquity);
    }

    private static double daysBetween(Instant a, Instant b) {
        long millis = java.time.Duration.between(a, b).toMillis();
        return Math.abs(millis) / 86_400_000.0;
    }
}