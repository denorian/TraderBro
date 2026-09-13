package com.traderbro.core.backtest;

import com.traderbro.core.domain.Instrument;
import com.traderbro.core.strategies.StrategyParams;
import com.traderbro.core.strategies.TradingStrategy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import lombok.extern.slf4j.Slf4j;
import org.ta4j.core.BarSeries;

/**
 * Walk-forward optimization: slides train/test windows over the full series, picks the best
 * parameter set on each <em>train</em> window by the configured objective, and evaluates that
 * set honestly on the <em>test</em> window (never seen during selection).
 */
@Slf4j
public class WalkForwardRunner {

    public enum Objective {
        SHARPE,
        SORTINO,
        CALMAR,
        TOTAL_RETURN
    }

    private final BacktestRunner runner;

    public WalkForwardRunner(BacktestRunner runner) {
        this.runner = runner;
    }

    /**
     * @param strategy     strategy to optimize
     * @param series       full series (DecimalNum)
     * @param instrument   instrument for lot sizing
     * @param trainBars    train window length (bars)
     * @param testBars     test window length (bars)
     * @param paramGrid    candidate parameter sets
     * @param objective    metric maximized on train
     * @param commissionPct, slippageBps, capital, maxPositionPct — shared across windows
     */
    public WalkForwardResult run(TradingStrategy strategy, BarSeries series, Instrument instrument,
                                 int trainBars, int testBars, List<Map<String, String>> paramGrid,
                                 Objective objective, BigDecimal commissionPct, BigDecimal slippageBps,
                                 BigDecimal capital, BigDecimal maxPositionPct) {
        List<WalkForwardWindow> windows = new ArrayList<>();
        int end = series.getEndIndex();
        int start = 0;
        while (start + trainBars + testBars <= end) {
            BarSeries train = series.getSubSeries(start, start + trainBars);
            BarSeries test = series.getSubSeries(start + trainBars, start + trainBars + testBars);

            Map<String, String> bestParams = selectBest(strategy, train, instrument, paramGrid,
                    objective, commissionPct, slippageBps, capital, maxPositionPct);
            BacktestMetrics trainMetrics = runner.run(strategy, train, new StrategyParams(bestParams),
                    instrument, commissionPct, slippageBps, capital, maxPositionPct);
            BacktestMetrics testMetrics = runner.run(strategy, test, new StrategyParams(bestParams),
                    instrument, commissionPct, slippageBps, capital, maxPositionPct);

            windows.add(new WalkForwardWindow(
                    barTime(train, 0), barTime(train, train.getEndIndex()),
                    barTime(test, 0), barTime(test, test.getEndIndex()),
                    bestParams, trainMetrics, testMetrics));
            start += testBars;
        }

        double sharpe = 0, ret = 0, dd = 0;
        long trades = 0;
        for (WalkForwardWindow w : windows) {
            sharpe += w.getTestMetrics().sharpe();
            ret += w.getTestMetrics().totalReturn();
            dd += w.getTestMetrics().maxDrawdown();
            trades += w.getTestMetrics().numTrades();
        }
        int n = windows.size();
        return new WalkForwardResult(windows,
                n == 0 ? 0 : sharpe / n,
                n == 0 ? 0 : ret / n,
                n == 0 ? 0 : dd / n,
                trades);
    }

    private Map<String, String> selectBest(TradingStrategy strategy, BarSeries train, Instrument instrument,
                                           List<Map<String, String>> grid, Objective objective,
                                           BigDecimal commissionPct, BigDecimal slippageBps,
                                           BigDecimal capital, BigDecimal maxPositionPct) {
        ToDoubleFunction<BacktestMetrics> obj = switch (objective) {
            case SHARPE -> BacktestMetrics::sharpe;
            case SORTINO -> BacktestMetrics::sortino;
            case CALMAR -> BacktestMetrics::calmar;
            case TOTAL_RETURN -> BacktestMetrics::totalReturn;
        };
        Map<String, String> best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Map<String, String> candidate : grid) {
            BacktestMetrics m;
            try {
                m = runner.run(strategy, train, new StrategyParams(candidate), instrument,
                        commissionPct, slippageBps, capital, maxPositionPct);
            } catch (RuntimeException e) {
                log.warn("walk-forward: candidate {} failed: {}", candidate, e.getMessage());
                continue;
            }
            double score = obj.applyAsDouble(m);
            if (Double.isFinite(score) && score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best == null) {
            throw new IllegalStateException("walk-forward: no valid parameter set in grid");
        }
        return best;
    }

    private Instant barTime(BarSeries s, int index) {
        if (index < 0 || index >= s.getBarCount()) {
            return null;
        }
        return s.getBar(index).getEndTime().toInstant();
    }
}