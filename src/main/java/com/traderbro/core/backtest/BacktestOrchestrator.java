package com.traderbro.core.backtest;

import com.traderbro.api.config.BacktestProperties;
import com.traderbro.api.config.CryptoProperties;
import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.StrategyConfig;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.domain.spi.BarStore;
import com.traderbro.core.domain.spi.StrategyConfigProvider;
import com.traderbro.core.event.NotificationLevel;
import com.traderbro.core.event.NotificationType;
import com.traderbro.core.event.TraderEvent;
import com.traderbro.core.event.TraderEventPublisher;
import com.traderbro.core.indicators.SeriesFactory;
import com.traderbro.core.strategies.StrategyParams;
import com.traderbro.core.strategies.StrategyRegistry;
import com.traderbro.core.strategies.TradingStrategy;
import com.traderbro.storage.BacktestBatchRepository;
import com.traderbro.storage.InstrumentRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.ta4j.core.BarSeries;
import org.ta4j.core.num.DecimalNum;

/**
 * Facade over {@link BacktestRunner} that orchestrates batch runs (multiple strategies ×
 * instruments) asynchronously on a dedicated, small executor pool. A backtest never touches the
 * live trading path: it runs in isolation, reads only persisted bars, and never writes to
 * {@code orders}/{@code positions} nor calls the broker gateway.
 *
 * <p>Lifecycle: {@code QUEUED → RUNNING → COMPLETED / FAILED / CANCELLED}. Each status change is
 * persisted and, on completion, a {@code BACKTEST_COMPLETED} event is published for Telegram.
 */
@Slf4j
public class BacktestOrchestrator {

    private final BacktestRunner runner;
    private final WalkForwardRunner walkForwardRunner;
    private final BarStore barStore;
    private final InstrumentRepository instruments;
    private final BacktestBatchRepository repository;
    private final StrategyRegistry registry;
    private final StrategyConfigProvider configProvider;
    private final TraderEventPublisher publisher;
    private final String codeVersion;
    private final BacktestProperties props;
    private final CryptoProperties cryptoProps;
    private final ExecutorService executor;
    private final Map<String, Future<?>> futures = new ConcurrentHashMap<>();

    public BacktestOrchestrator(BacktestRunner runner, WalkForwardRunner walkForwardRunner,
                                BarStore barStore, InstrumentRepository instruments,
                                BacktestBatchRepository repository, StrategyRegistry registry,
                                StrategyConfigProvider configProvider,
                                TraderEventPublisher publisher, String codeVersion,
                                BacktestProperties props, CryptoProperties cryptoProps) {
        this.runner = runner;
        this.walkForwardRunner = walkForwardRunner;
        this.barStore = barStore;
        this.instruments = instruments;
        this.repository = repository;
        this.registry = registry;
        this.configProvider = configProvider;
        this.publisher = publisher;
        this.codeVersion = codeVersion;
        this.props = props;
        this.cryptoProps = cryptoProps;
        // Isolated pool: backtests must not compete with the live trading loop for CPU.
        this.executor = Executors.newFixedThreadPool(props.getExecutorPoolSize(),
                r -> {
                    Thread t = new Thread(r, "backtest-" + r.hashCode());
                    t.setDaemon(true);
                    return t;
                });
    }

    /** Validates the request, enqueues a run and returns its id ({@code QUEUED}). */
    public String submit(BacktestRequest request) {
        validate(request);
        String id = UUID.randomUUID().toString();
        BacktestRun run = BacktestRun.queued(id, request, codeVersion);
        repository.insertRun(run);
        Future<?> future = executor.submit(() -> execute(id, request));
        futures.put(id, future);
        log.info("backtest {} queued: {} strategies × {} instruments, interval {}",
                id, request.strategyIds().size(), request.figis().size(), request.interval());
        return id;
    }

    /** Cancels a QUEUED/RUNNING run via {@link Future#cancel(true)} plus cooperative checks. */
    public boolean cancel(String id) {
        Future<?> future = futures.get(id);
        if (future == null) {
            return false;
        }
        boolean cancelled = future.cancel(true);
        if (cancelled) {
            repository.updateStatus(id, BacktestRunStatus.CANCELLED,
                    repository.findRun(id).map(BacktestRun::getProgressPct).orElse(0),
                    "cancelled by user", null, Instant.now());
        }
        return cancelled;
    }

    private void execute(String id, BacktestRequest request) {
        try {
            repository.updateStatus(id, BacktestRunStatus.RUNNING, 0, null, Instant.now(), null);
            List<PairSpec> pairs = pairSpecs(request);
            AtomicInteger done = new AtomicInteger();
            for (PairSpec pair : pairs) {
                checkCancelled();
                runPair(id, request, pair);
                int progress = (int) Math.round(100.0 * done.incrementAndGet() / pairs.size());
                repository.updateProgress(id, progress);
            }
            repository.updateStatus(id, BacktestRunStatus.COMPLETED, 100, null, null, Instant.now());
            log.info("backtest {} completed ({} pairs)", id, pairs.size());
            publishCompleted(id, request, pairs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            repository.updateStatus(id, BacktestRunStatus.CANCELLED, 0,
                    "interrupted", null, Instant.now());
            log.info("backtest {} cancelled", id);
        } catch (Exception e) {
            log.error("backtest {} failed: {}", id, e.getMessage(), e);
            repository.updateStatus(id, BacktestRunStatus.FAILED, 0,
                    String.valueOf(e.getMessage()), null, Instant.now());
        } finally {
            futures.remove(id);
        }
    }

    private void runPair(String runId, BacktestRequest request, PairSpec pair) {
        Instrument instrument = instruments.findByFigi(pair.figi())
                .orElseThrow(() -> new IllegalArgumentException("unknown figi: " + pair.figi()));
        TradingStrategy strategy = registry.factory(pair.strategyId());
        if (strategy == null) {
            throw new IllegalArgumentException("unknown strategy: " + pair.strategyId());
        }
        Map<String, String> params = resolveParams(pair.strategyId(), request);
        List<Bar> bars = barStore.findByFigiAndInterval(pair.figi(), request.interval(),
                request.from(), request.to());
        BarSeries series = SeriesFactory.fromDomain(bars, DecimalNum::valueOf, bars.size() + 10,
                pair.figi());
        BacktestCosts costs = resolveCosts(request.costs());
        // Crypto spot uses Binance's taker fee by default (0.1%) unless the caller overrode it.
        if (instrument.isCrypto() && request.costs() == null) {
            costs = new BacktestCosts(
                    BigDecimal.valueOf(cryptoProps.getTakerFeePct()).movePointLeft(2),
                    cryptoProps.getSlippageBps(),
                    costs.initialCapital(), costs.maxPositionPct());
        }

        PairResult result;
        if (request.walkForward()) {
            // TODO(stage 4): persist walk-forward window details (trades/signals/equity) too.
            result = runWalkForward(strategy, series, instrument, params, costs, request);
        } else if (instrument.isFuture()) {
            result = simulateFutures(strategy, series, instrument, params, costs);
        } else {
            result = simulateShares(strategy, series, instrument, params, costs);
        }
        repository.insertPairResult(runId, result);
        repository.insertTrades(runId, result.getTrades());
        repository.insertSignals(runId, result.getSignals());
        repository.insertEquity(runId, pair.strategyId(), pair.figi(), result.getEquity());
    }

    private PairResult simulateShares(TradingStrategy strategy, BarSeries series,
                                      Instrument instrument, Map<String, String> params,
                                      BacktestCosts costs) {
        SimulationResult sim = runner.simulateShares(strategy, series, new StrategyParams(params),
                instrument, costs.commissionPct(), BigDecimal.valueOf(costs.slippageBps()),
                costs.initialCapital(), costs.maxPositionPct());
        return PairResult.builder()
                .strategyId(strategy.id()).figi(instrument.getFigi())
                .metrics(sim.metrics()).barsCount(series.getBarCount())
                .trades(sim.trades()).signals(sim.signals()).equity(sim.equity()).build();
    }

    private PairResult simulateFutures(TradingStrategy strategy, BarSeries series,
                                       Instrument instrument, Map<String, String> params,
                                       BacktestCosts costs) {
        double ref = series.getBarCount() > 0
                ? series.getBar(0).getOpenPrice().doubleValue() : 0.0;
        double tick = instrument.getFutureSpec().getMinPriceIncrement().doubleValue();
        BigDecimal feePerContract = BigDecimal.valueOf(ref)
                .multiply(costs.commissionPct());
        int slippageTicks = tick > 0
                ? (int) Math.round(costs.slippageBps() / 10_000.0 * ref / tick)
                : 0;
        SimulationResult sim = runner.simulateFutures(strategy, series, new StrategyParams(params),
                instrument, feePerContract, slippageTicks, costs.initialCapital(),
                costs.maxPositionPct());
        return PairResult.builder()
                .strategyId(strategy.id()).figi(instrument.getFigi())
                .metrics(sim.metrics()).barsCount(series.getBarCount())
                .trades(sim.trades()).signals(sim.signals()).equity(sim.equity()).build();
    }

    private PairResult runWalkForward(TradingStrategy strategy, BarSeries series,
                                      Instrument instrument, Map<String, String> params,
                                      BacktestCosts costs, BacktestRequest request) {
        int trainDays = request.walkForwardTrainDays() != null
                ? request.walkForwardTrainDays() : props.getWalkForward().getTrainDays();
        int testDays = request.walkForwardTestDays() != null
                ? request.walkForwardTestDays() : props.getWalkForward().getTestDays();
        List<Map<String, String>> grid = request.walkForwardGrid();
        if (grid == null || grid.isEmpty()) {
            grid = defaultGrid(params);
        }
        WalkForwardResult wf = walkForwardRunner.run(strategy, series, instrument,
                trainDays, testDays, grid,
                WalkForwardRunner.Objective.SHARPE, costs.commissionPct(),
                BigDecimal.valueOf(costs.slippageBps()), costs.initialCapital(),
                costs.maxPositionPct());
        double testReturn = wf.getAvgTestTotalReturn();
        double finalEquity = costs.initialCapital().doubleValue() * (1 + testReturn);
        BacktestMetrics metrics = BacktestMetrics.fromRun(
                List.of(costs.initialCapital().doubleValue(), finalEquity),
                finalEquity, costs.initialCapital().doubleValue(), testReturn, List.of(),
                series, 2);
        return PairResult.builder().strategyId(strategy.id()).figi(instrument.getFigi())
                .metrics(metrics).barsCount(series.getBarCount())
                .trades(List.of()).signals(List.of()).equity(List.of()).build();
    }

    /**
     * Builds a small parameter grid for walk-forward optimisation when the request carries no
     * explicit {@code walkForwardGrid}: for each numeric parameter of the resolved set, add
     * variants scaled to 80% and 120%. One parameter is varied at a time so the grid stays small
     * while still letting the walk-forward pick a better point on train windows.
     */
    private List<Map<String, String>> defaultGrid(Map<String, String> base) {
        List<Map<String, String>> grid = new java.util.ArrayList<>();
        grid.add(base);
        for (java.util.Map.Entry<String, String> e : base.entrySet()) {
            double v;
            try {
                v = Double.parseDouble(e.getValue());
            } catch (NumberFormatException nfe) {
                continue; // non-numeric parameters are not optimised
            }
            if (v <= 0) {
                continue;
            }
            for (double factor : new double[]{0.8, 1.2}) {
                Map<String, String> variant = new java.util.HashMap<>(base);
                variant.put(e.getKey(), String.valueOf(Math.round(v * factor * 100) / 100.0));
                grid.add(variant);
            }
        }
        return grid;
    }

    private Map<String, String> resolveParams(String strategyId, BacktestRequest request) {
        Object override = request.strategyParamsOverrides() == null
                ? null : request.strategyParamsOverrides().get(strategyId);
        if (override instanceof Map<?, ?> m) {
            Map<String, String> out = new java.util.HashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
            return out;
        }
        return configProvider.findById(strategyId)
                .map(StrategyConfig::getParams)
                .orElse(Map.of());
    }

    private BacktestCosts defaultCosts() {
        return new BacktestCosts(
                props.getDefaultCommissionPct().movePointLeft(2), // percent → fraction
                props.getDefaultSlippageBps(), new BigDecimal("1000000"),
                new BigDecimal("0.95"));
    }

    /** Merges the request costs with config defaults, so partial requests (e.g. UI sends only
     * commission/slippage) still get sane capital and position limits. */
    private BacktestCosts resolveCosts(BacktestCosts c) {
        if (c == null) {
            return defaultCosts();
        }
        BacktestCosts d = defaultCosts();
        return new BacktestCosts(
                c.commissionPct() != null ? c.commissionPct() : d.commissionPct(),
                c.slippageBps(),
                c.initialCapital() != null ? c.initialCapital() : d.initialCapital(),
                c.maxPositionPct() != null ? c.maxPositionPct() : d.maxPositionPct());
    }

    private List<PairSpec> pairSpecs(BacktestRequest request) {
        List<PairSpec> out = new ArrayList<>();
        for (String strategyId : request.strategyIds()) {
            for (String figi : request.figis()) {
                out.add(new PairSpec(strategyId, figi));
            }
        }
        return out;
    }

    private void checkCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("backtest cancelled");
        }
    }

    private void publishCompleted(String id, BacktestRequest request, List<PairSpec> pairs) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("runId", id);
        payload.put("strategies", request.strategyIds());
        payload.put("figis", request.figis());
        payload.put("interval", request.interval().name());
        payload.put("pairs", pairs.size());
        payload.put("restUrl", "/api/backtests/" + id);
        publisher.publish(TraderEvent.of(NotificationType.BACKTEST_COMPLETED,
                NotificationLevel.INFO, payload));
    }

    private void validate(BacktestRequest request) {
        if (request.strategyIds() == null || request.strategyIds().isEmpty()) {
            throw new IllegalArgumentException("strategyIds must not be empty");
        }
        if (request.figis() == null || request.figis().isEmpty()) {
            throw new IllegalArgumentException("figis must not be empty");
        }
        if (request.interval() == null) {
            throw new IllegalArgumentException("interval is required");
        }
        if (request.from() == null || request.to() == null || !request.from().isBefore(request.to())) {
            throw new IllegalArgumentException("from must be before to");
        }
        for (String strategyId : request.strategyIds()) {
            if (registry.factory(strategyId) == null) {
                throw new IllegalArgumentException("unknown strategy: " + strategyId);
            }
        }
        for (String figi : request.figis()) {
            if (instruments.findByFigi(figi).isEmpty()) {
                throw new IllegalArgumentException("unknown figi: " + figi);
            }
        }
    }

    /** Strategy × instrument pair key. */
    private record PairSpec(String strategyId, String figi) {
    }

    /** Shuts down the executor on application stop. */
    public void shutdown() {
        executor.shutdownNow();
    }
}