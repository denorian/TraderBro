package com.traderbro.core.backtest;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Exports {@link BacktestReport} metrics to a human-readable Markdown table
 * (written under {@code reports/backtests/}).
 */
public final class MarkdownExporter {

    private static final NumberFormat PCT = new DecimalFormat("0.00%", new java.text.DecimalFormatSymbols(Locale.US));
    private static final NumberFormat NUM = new DecimalFormat("0.000", new java.text.DecimalFormatSymbols(Locale.US));

    private MarkdownExporter() {
    }

    public static String toMarkdown(BacktestReport report) {
        BacktestMetrics m = report.getMetrics();
        StringBuilder sb = new StringBuilder();
        sb.append("# Backtest report\n\n");
        sb.append("| | |\n|---|---|\n");
        sb.append("| id | `").append(report.getId()).append("` |\n");
        sb.append("| strategy | ").append(report.getStrategyId()).append(" |\n");
        sb.append("| figi | ").append(report.getFigi()).append(" |\n");
        sb.append("| interval | ").append(report.getInterval()).append(" |\n");
        sb.append("| from | ").append(report.getFrom()).append(" |\n");
        sb.append("| to | ").append(report.getTo()).append(" |\n");
        sb.append("| bars | ").append(report.getBarsCount()).append(" |\n");
        sb.append("| code version | ").append(report.getCodeVersion()).append(" |\n");
        sb.append("| created | ").append(report.getCreatedAt()).append(" |\n");
        sb.append("\n## Metrics\n\n");
        sb.append("| Metric | Value |\n|---|---|\n");
        sb.append("| Total return | ").append(PCT.format(m.totalReturn())).append(" |\n");
        sb.append("| Buy & hold return | ").append(PCT.format(m.buyAndHoldReturn())).append(" |\n");
        sb.append("| CAGR | ").append(PCT.format(m.cagr())).append(" |\n");
        sb.append("| Sharpe | ").append(NUM.format(m.sharpe())).append(" |\n");
        sb.append("| Sortino | ").append(NUM.format(m.sortino())).append(" |\n");
        sb.append("| Calmar | ").append(NUM.format(m.calmar())).append(" |\n");
        sb.append("| Max drawdown | ").append(PCT.format(m.maxDrawdown())).append(" |\n");
        sb.append("| Win rate | ").append(PCT.format(m.winRate())).append(" |\n");
        sb.append("| Profit factor | ").append(NUM.format(m.profitFactor())).append(" |\n");
        sb.append("| Trades | ").append(m.numTrades()).append(" |\n");
        sb.append("| Avg holding (bars) | ").append(NUM.format(m.avgBarsHeld())).append(" |\n");
        sb.append("| Final equity | ").append(NUM.format(m.finalEquity())).append(" |\n");
        sb.append("\n## Parameters\n\n```json\n");
        sb.append(toJson(report.getParams()));
        sb.append("\n```\n");
        return sb.toString();
    }

    private static String toJson(java.util.Map<String, String> params) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : params.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append('"').append(e.getKey()).append("\": \"").append(e.getValue()).append('"');
            first = false;
        }
        return sb.append("}").toString();
    }
}