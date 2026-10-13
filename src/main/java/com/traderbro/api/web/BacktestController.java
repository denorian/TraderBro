package com.traderbro.api.web;

import com.traderbro.core.backtest.BacktestMetrics;
import com.traderbro.core.backtest.BacktestOrchestrator;
import com.traderbro.core.backtest.BacktestRequest;
import com.traderbro.core.backtest.BacktestRun;
import com.traderbro.core.backtest.BacktestRunStatus;
import com.traderbro.core.backtest.EquityPoint;
import com.traderbro.core.backtest.PairResult;
import com.traderbro.core.backtest.SignalRecord;
import com.traderbro.core.backtest.TradeRecord;
import com.traderbro.core.domain.StrategyConfig;
import com.traderbro.core.domain.spi.StrategyConfigProvider;
import com.traderbro.core.strategies.StrategyRegistry;
import com.traderbro.storage.BacktestBatchRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for orchestrated backtests. Launching a backtest is the only write operation the UI
 * performs, and it is deliberately safe: it runs in an isolated pool, reads only persisted bars
 * and never touches the live trading circuit (no orders/positions, no broker gateway).
 * All endpoints are behind the shared {@code X-Api-Key}.
 */
@RestController
@RequestMapping("/api/backtests")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestOrchestrator orchestrator;
    private final BacktestBatchRepository repository;
    private final StrategyRegistry registry;
    private final StrategyConfigProvider configProvider;

    public record RunSummary(String id, String status, int progressPct, String errorMessage,
                             BacktestRequest request, Instant createdAt, Instant startedAt,
                             Instant finishedAt) {
    }

    public record CreateResponse(String id, String status) {
    }

    public record MetricsDto(double totalReturn, double cagr, double sharpe, double sortino,
                             double calmar, double maxDrawdown, double winRate,
                             double profitFactor, long numTrades, double avgBarsHeld,
                             double finalEquity) {
        static MetricsDto from(BacktestMetrics m) {
            return new MetricsDto(m.totalReturn(), m.cagr(), m.sharpe(), m.sortino(), m.calmar(),
                    m.maxDrawdown(), m.winRate(), m.profitFactor(), m.numTrades(),
                    m.avgBarsHeld(), m.finalEquity());
        }
    }

    public record PairResultDto(String strategyId, String figi, MetricsDto metrics, long barsCount,
                                List<EquityPoint> equity, List<TradeRecord> trades,
                                List<SignalRecord> signals) {
        static PairResultDto light(PairResult p) {
            return new PairResultDto(p.getStrategyId(), p.getFigi(), MetricsDto.from(p.getMetrics()),
                    p.getBarsCount(), List.of(), List.of(), List.of());
        }
    }

    public record RunDetailDto(RunSummary run, List<PairResultDto> results) {
    }

    public record CompareRowDto(String runId, String strategyId, String figi, MetricsDto metrics) {
    }

    /** Creates a backtest run. 202 Accepted with the run id. */
    @PostMapping
    public ResponseEntity<CreateResponse> create(@RequestBody BacktestRequest request) {
        try {
            String id = orchestrator.submit(request);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new CreateResponse(id, BacktestRunStatus.QUEUED));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(null);
        }
    }

    /** Lists run headers (batch runs only), newest first, with optional filters + pagination. */
    @GetMapping
    public List<RunSummary> list(@RequestParam(required = false) String status,
                                 @RequestParam(required = false) Instant from,
                                 @RequestParam(required = false) Instant to,
                                 @RequestParam(defaultValue = "20") int limit,
                                 @RequestParam(defaultValue = "0") long offset) {
        return repository.findRuns(status, from, to, Math.min(Math.max(limit, 1), 100), offset)
                .stream().map(BacktestController::toSummary).toList();
    }

    /** Details for one run: header + lightweight per-pair metrics. */
    @GetMapping("/{id}")
    public ResponseEntity<RunDetailDto> get(@PathVariable String id) {
        Optional<BacktestRun> run = repository.findRun(id);
        if (run.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<PairResultDto> pairs = repository.findPairResults(id).stream()
                .map(PairResultDto::light).toList();
        return ResponseEntity.ok(new RunDetailDto(toSummary(run.get()), pairs));
    }

    /** Full results: per-pair metrics, equity curves, trades and signals. */
    @GetMapping("/{id}/results")
    public ResponseEntity<List<PairResultDto>> results(@PathVariable String id) {
        if (repository.findRun(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<PairResultDto> out = new java.util.ArrayList<>();
        for (PairResult p : repository.findPairResults(id)) {
            out.add(new PairResultDto(p.getStrategyId(), p.getFigi(),
                    MetricsDto.from(p.getMetrics()), p.getBarsCount(),
                    repository.findEquity(id, p.getStrategyId(), p.getFigi()),
                    repository.findTrades(id, p.getFigi(), p.getStrategyId(), 100_000, 0),
                    repository.findSignals(id, p.getFigi(), p.getStrategyId(), 100_000, 0)));
        }
        return ResponseEntity.ok(out);
    }

    /** Signal points for chart pins, filtered by figi/strategyId, paginated. */
    @GetMapping("/{id}/signals")
    public ResponseEntity<List<SignalRecord>> signals(@PathVariable String id,
                                                      @RequestParam(required = false) String figi,
                                                      @RequestParam(required = false) String strategyId,
                                                      @RequestParam(defaultValue = "1000") int limit,
                                                      @RequestParam(defaultValue = "0") long offset) {
        if (repository.findRun(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(repository.findSignals(id, figi, strategyId,
                Math.min(Math.max(limit, 1), 10_000), offset));
    }

    /** Closed trades, filtered by figi/strategyId, paginated. */
    @GetMapping("/{id}/trades")
    public ResponseEntity<List<TradeRecord>> trades(@PathVariable String id,
                                                    @RequestParam(required = false) String figi,
                                                    @RequestParam(required = false) String strategyId,
                                                    @RequestParam(defaultValue = "1000") int limit,
                                                    @RequestParam(defaultValue = "0") long offset) {
        if (repository.findRun(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(repository.findTrades(id, figi, strategyId,
                Math.min(Math.max(limit, 1), 10_000), offset));
    }

    /** Cancels a QUEUED/RUNNING run, or deletes a finished run and its results. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        Optional<BacktestRun> run = repository.findRun(id);
        if (run.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String status = run.get().getStatus();
        if (status.equals(BacktestRunStatus.QUEUED) || status.equals(BacktestRunStatus.RUNNING)) {
            return orchestrator.cancel(id)
                    ? ResponseEntity.accepted().build()
                    : ResponseEntity.notFound().build();
        }
        repository.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Comparative metrics table across up to 5 runs. */
    @GetMapping("/compare")
    public ResponseEntity<List<CompareRowDto>> compare(@RequestParam List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 5) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(repository.findPairsForCompare(ids).stream()
                .map(p -> new CompareRowDto(p.runId(), p.strategyId(), p.figi(),
                        MetricsDto.from(p.metrics())))
                .toList());
    }

    /** Available strategies for the launch form. */
    @GetMapping("/strategies")
    public List<StrategyDto> strategies() {
        return registry.ids().stream().sorted().map(id -> {
            Optional<StrategyConfig> cfg = configProvider.findById(id);
            return new StrategyDto(id, id, "стратегия " + id,
                    cfg.map(StrategyConfig::getParams).orElse(Map.of()));
        }).toList();
    }

    public record StrategyDto(String id, String name, String description, Map<String, String> params) {
    }

    private static RunSummary toSummary(BacktestRun run) {
        return new RunSummary(run.getId(), run.getStatus(), run.getProgressPct(), run.getErrorMessage(),
                run.getRequest(), run.getCreatedAt(), run.getStartedAt(), run.getFinishedAt());
    }
}