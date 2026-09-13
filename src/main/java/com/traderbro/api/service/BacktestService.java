package com.traderbro.api.service;

import com.traderbro.core.backtest.BacktestMetrics;
import com.traderbro.core.backtest.BacktestReport;
import com.traderbro.core.backtest.BacktestRunner;
import com.traderbro.core.backtest.MarkdownExporter;
import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.domain.spi.BarStore;
import com.traderbro.core.indicators.SeriesFactory;
import com.traderbro.core.strategies.StrategyParams;
import com.traderbro.core.strategies.TradingStrategy;
import com.traderbro.storage.BacktestRunRepository;
import com.traderbro.storage.InstrumentRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.ta4j.core.BarSeries;
import org.ta4j.core.num.DecimalNum;

/**
 * Orchestrates backtests over persisted bars: runs the strategy, persists the report and
 * exports a Markdown file under {@code reports/backtests/}.
 */
@Slf4j
public class BacktestService {

    private final BacktestRunner runner;
    private final BarStore barStore;
    private final InstrumentRepository instrumentRepository;
    private final BacktestRunRepository runRepository;
    private final String codeVersion;
    private final String reportsDir;
    private final int moneyScale;

    public BacktestService(BacktestRunner runner, BarStore barStore,
                           InstrumentRepository instrumentRepository,
                           BacktestRunRepository runRepository, String codeVersion,
                           String reportsDir, int moneyScale) {
        this.runner = runner;
        this.barStore = barStore;
        this.instrumentRepository = instrumentRepository;
        this.runRepository = runRepository;
        this.codeVersion = codeVersion;
        this.reportsDir = reportsDir;
        this.moneyScale = moneyScale;
    }

    public BacktestReport runBacktest(TradingStrategy strategy, Instrument instrument,
                                      CandleInterval interval, Instant from, Instant to,
                                      Map<String, String> params,
                                      BigDecimal commissionPct, BigDecimal slippageBps,
                                      BigDecimal initialCapital) {
        List<Bar> bars = barStore.findByFigiAndInterval(instrument.getFigi(), interval, from, to);
        BarSeries series = SeriesFactory.fromDomain(bars, DecimalNum::valueOf, bars.size() + 10,
                instrument.getFigi());
        StrategyParams strategyParams = new StrategyParams(params);
        BigDecimal maxPct = new BigDecimal("0.95");
        BacktestMetrics metrics = runner.run(strategy, series, strategyParams, instrument,
                commissionPct, slippageBps, initialCapital, maxPct);

        BacktestReport report = BacktestReport.builder()
                .id(UUID.randomUUID().toString())
                .strategyId(strategy.id())
                .figi(instrument.getFigi())
                .interval(interval)
                .from(from)
                .to(to)
                .params(params)
                .metrics(metrics)
                .codeVersion(codeVersion)
                .createdAt(Instant.now())
                .barsCount(bars.size())
                .build();
        runRepository.insert(report);
        exportMarkdown(report);
        return report;
    }

    private void exportMarkdown(BacktestReport report) {
        try {
            Path dir = Path.of(reportsDir, "backtests");
            Files.createDirectories(dir);
            Path file = dir.resolve(report.getId() + ".md");
            Files.writeString(file, MarkdownExporter.toMarkdown(report), StandardCharsets.UTF_8);
            log.info("backtest report written to {}", file.toAbsolutePath());
        } catch (IOException e) {
            log.error("failed to export backtest report: {}", e.getMessage());
        }
    }
}